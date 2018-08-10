/*
 * Copyright (c) IBM Corporation 2018. All Rights Reserved.
 * Project name: pross
 * This project is licensed under the MIT License, see LICENSE.
 */

package com.ibm.pross.server.shareholder.state;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ibm.pross.common.CommonConfiguration;
import com.ibm.pross.common.util.crypto.EciesEncryption;
import com.ibm.pross.common.util.crypto.ecc.EcCurve;
import com.ibm.pross.common.util.crypto.ecc.EcPoint;
import com.ibm.pross.common.util.shamir.Shamir;
import com.ibm.pross.common.util.shamir.ShamirShare;
import com.ibm.pross.server.Channel;
import com.ibm.pross.server.messages.EncryptedPayload;
import com.ibm.pross.server.messages.Message;
import com.ibm.pross.server.messages.Payload;
import com.ibm.pross.server.messages.SemiPrivateMessage;
import com.ibm.pross.server.messages.SignedMessage;
import com.ibm.pross.server.messages.payloads.VssPrivatePayload;
import com.ibm.pross.server.messages.payloads.dkg.GenerationAccusations;
import com.ibm.pross.server.messages.payloads.dkg.GenerationRebuttal;
import com.ibm.pross.server.messages.payloads.dkg.GenerationVssPublicPayload;
import com.ibm.pross.server.shareholder.Shareholder;

public class GenerationStateTracker {

	// Static fields
	final public static EcCurve curve = CommonConfiguration.CURVE;
	final public static BigInteger r = curve.getR();
	final public static EcPoint G = curve.getG();

	// Shareholder who is managing this state
	final Shareholder shareholder;

	// Messages
	final SignedMessage ourSignedUpdateMessage;
	final ConcurrentMap<Integer, SignedMessage> receivedMessages = new ConcurrentHashMap<>();
	private final SortedSet<Integer> ourAccusations = new TreeSet<>();
	final ConcurrentMap<Integer, GenerationAccusations> receivedAccusations = new ConcurrentHashMap<>();
	final ConcurrentMap<SimpleEntry<Integer, Integer>, GenerationRebuttal> receivedRebuttals = new ConcurrentHashMap<>();

	// Parameters related to threshold
	private final int threshold;
	private final int updateThreshold;
	private final int n;

	// Internal state for update operation created by us
	private final BigInteger[] coefficients;
	private final ShamirShare[] shares;
	private final EcPoint[] coefficientPowers;

	enum States {
		INITALIZED, SENT_UPDATE, RECEIVED_UPDATES, VERIFIED_UPDATES, MADE_ACCUSATIONS, SENT_REBUTTLES, PROCESSED_REBUTTALS, IDENTIFIED_CORRUPTIONS, ACHIEVED_UPDATE_THRESHOLD, PERFORMED_UPDATE;
	}

	private volatile States currentState;

	private volatile EcPoint secretPublicKey;

	public GenerationStateTracker(final Shareholder shareholder, final int threshold, final int updateThreshold,
			final int n) {

		this.shareholder = shareholder;

		this.threshold = threshold;
		this.updateThreshold = updateThreshold;
		this.n = n;

		// Figure 1, step 1

		// Generate co-efficients of a random t-1 degree polynomial
		this.coefficients = Shamir.generateCoefficients(this.threshold);

		// Produce partial shares for everyone else
		this.shares = Shamir.generateShares(coefficients, this.n);

		// polynomial coefficients times G -- (G^f_i) the feldmen proofs
		this.coefficientPowers = Shamir.generateFeldmanValues(coefficients);

		// Build a single semi-private message, to be sent as part of our update
		final GenerationVssPublicPayload publicPayload = new GenerationVssPublicPayload(this.coefficientPowers);

		final Map<Integer, Payload> privatePayloads = new HashMap<>();
		for (int i = 0; i < n; i++) {
			privatePayloads.put(i, new VssPrivatePayload(this.shares[i]));
		}

		final Message unsignedMessage = this.shareholder.createSemiPrivateMessage(publicPayload, privatePayloads);
		this.ourSignedUpdateMessage = this.shareholder.createSignedMessage(unsignedMessage);

		this.currentState = States.INITALIZED;
	}

	public SignedMessage getOurSignedUpdateMessage() {
		return ourSignedUpdateMessage;
	}

	public synchronized void sendOurSignedUpdateMessage(final Channel channel) {
		if (this.currentState.equals(States.INITALIZED)) {
			channel.broadcast(ourSignedUpdateMessage);
			this.currentState = States.SENT_UPDATE;
		} else {
			throw new IllegalStateException("Must be in States.INITIALIZED");
		}
	}

	/**
	 * Persist all received messages
	 * 
	 * @param sender
	 * @param signedMessage
	 * @param decryptionKey
	 */
	public void saveVssMessage(int sender, final SignedMessage signedMessage, final PrivateKey decryptionKey) {

		if (!(this.currentState.equals(States.SENT_UPDATE) || this.currentState.equals(States.INITALIZED))) {
			throw new IllegalStateException("Must be in States.SENT_UPDATE or States.INITIALIZED");
		}

		final SignedMessage previous = this.receivedMessages.putIfAbsent(sender, signedMessage);

		if (previous != null) {
			// Received duplicate message from this sender!
			System.err.println(
					"Shareholder [" + this.shareholder.getIndex() + "] received duplicate messages [" + sender + "]");
			this.getOurAccusations().add(sender);
		}

		// Checks to perform

		// Note: Signature checks already performed, we ignore messages with bad
		// signatures
		final SemiPrivateMessage message = (SemiPrivateMessage) signedMessage.getMessage();

		final GenerationVssPublicPayload vssPublic = (GenerationVssPublicPayload) message.getPublicPayload();

		// Expected number of Feldman parameters present (T)
		if (vssPublic.getFeldmanValues().length != this.threshold) {
			// Incorrect number of feldman values
			System.err.println("Shareholder [" + this.shareholder.getIndex() + "] wrong number of feldman values ["
					+ sender + "]");
			this.getOurAccusations().add(sender);
		}

		// Expected number of private values (N)
		// No duplicate entries of private values (all unique, 0 to N-1)
		for (int i = 0; i < this.n; i++) {
			final EncryptedPayload encryptedPayload = (EncryptedPayload) message.getEncryptedPayload(i);
			if (encryptedPayload == null) {
				// Incorrect number of encrypted values (some recipient didn't
				// get theirs)
				System.err.println(
						"Shareholder [" + this.shareholder.getIndex() + "] not enough recipients [" + sender + "]");
				this.getOurAccusations().add(sender);
			}

			if (i == this.shareholder.getIndex()) {
				// This is ours, we can try to decrypt it
				try {
					final Payload privatePayload = EciesEncryption.decryptPayload(encryptedPayload, decryptionKey);
					final VssPrivatePayload vssPrivate = (VssPrivatePayload) privatePayload;
					if ((vssPrivate.getShareUpdate().getX().intValue() - 1) != this.shareholder.getIndex()) {
						// Invalid x coordinate of share
						System.err.println("Shareholder [" + this.shareholder.getIndex()
								+ "] invalid share update from [" + sender + "]");
						this.getOurAccusations().add(sender);
					}
					if (vssPrivate.getShareUpdate().getY().compareTo(BigInteger.ZERO) < 0) {
						// Invalid y coordinate of share, it is negative!
						System.err.println("Shareholder [" + this.shareholder.getIndex()
								+ "] invalid share update from [" + sender + "]");
						this.getOurAccusations().add(sender);
					}
					if (vssPrivate.getShareUpdate().getY().compareTo(r) >= 0) {
						// Invalid y coordinate of share, it is too big!
						System.err.println("Shareholder [" + this.shareholder.getIndex()
								+ "] invalid share update from [" + sender + "]");
						this.getOurAccusations().add(sender);
					}

					// Perform feldman-based verification of our share update!
					Shamir.verifyShamirShareConsistency(vssPrivate.getShareUpdate(), vssPublic.getFeldmanValues());

				} catch (IllegalArgumentException e) {
					// Our share is inconsistent with the feldman verification
					System.err.println("Shareholder [" + this.shareholder.getIndex() + "] sent us an invalid share ["
							+ sender + "]");
					this.getOurAccusations().add(sender);
				} catch (Exception e) {
					// Failed to decrypt with our public key, need to make a
					// public accusation
					// Or it could be a cast error (in any case we need to force
					// them to make rebuttle)
					System.err.println("Shareholder [" + this.shareholder.getIndex()
							+ "] failed to decrypt share update from [" + sender + "]");
					this.getOurAccusations().add(sender);
				}
			}
		}

		// Ignore messages that are missing (Shareholder may be offline)
	}

	public synchronized void verifyUpdateMessages() {

		if (this.currentState.equals(States.SENT_UPDATE)) {
			this.currentState = States.RECEIVED_UPDATES;

			// Filter all of our saved messages according to accusations
			for (Integer accused : this.getOurAccusations()) {
				this.receivedMessages.remove(accused);
			}

			this.currentState = States.VERIFIED_UPDATES;

		} else {
			throw new IllegalStateException("Must be in States.SENT_UPDATE");
		}

	}

	public synchronized void sendAccusations() {
		if (this.currentState.equals(States.VERIFIED_UPDATES)) {

			// Construct accusation message
			final GenerationAccusations accusationPayload = new GenerationAccusations(this.getOurAccusations());

			this.shareholder.sendPublicMessage(accusationPayload);

			this.currentState = States.MADE_ACCUSATIONS;
		} else {
			throw new IllegalStateException("Must be in States.VERIFIED_UPDATES");
		}

	}

	public void saveAccusation(int sender, final GenerationAccusations payload) {

		if (!(this.currentState.equals(States.MADE_ACCUSATIONS) || this.currentState.equals(States.VERIFIED_UPDATES))) {
			throw new IllegalStateException("Must be in State.VERIFIED_UPDATES or States.MADE_ACCUSATIONS");
		}

		this.receivedAccusations.putIfAbsent(sender, payload);

	}

	public synchronized void sendRebuttals() {

		if (this.currentState.equals(States.MADE_ACCUSATIONS)) {
			this.currentState = States.SENT_REBUTTLES;

			// Determine if any accusations were made against us, and if so,
			// send a
			// rebuttle by disclosing the secret AES key used to wrap the
			// recipient's share
			SemiPrivateMessage semiPrivateMessage = (SemiPrivateMessage) this.getOurSignedUpdateMessage().getMessage();

			for (Integer accuser : this.receivedAccusations.keySet()) {
				final Set<Integer> accused = this.receivedAccusations.get(accuser).getAccused();
				if (accused.contains(this.shareholder.getIndex())) {

					// We've been accused! Send a public rebuttle.
					final byte[] keyAndNonce = semiPrivateMessage.getEncryptedPayload(accuser).rebuttalEvidence;
					this.shareholder.sendPublicMessage(new GenerationRebuttal(accuser, keyAndNonce));

				}
			}

		} else {
			throw new IllegalStateException("Must be in States.MADE_ACCUSATIONS");
		}

	}

	public void saveRebuttle(final int sender, final GenerationRebuttal rebuttle) {

		if (!(this.currentState.equals(States.MADE_ACCUSATIONS) || this.currentState.equals(States.SENT_REBUTTLES))) {
			throw new IllegalStateException("Must be in State.VERIFIED_UPDATES or States.MADE_ACCUSATIONS");
		}

		final int accuser = rebuttle.getAccuser();

		final SimpleEntry<Integer, Integer> senderAccuserPair = new SimpleEntry<>(sender, accuser);
		this.receivedRebuttals.putIfAbsent(senderAccuserPair, rebuttle);

	}

	public synchronized void processRebuttals() {

		if (this.currentState.equals(States.SENT_REBUTTLES)) {

			// For each accusation, see if a rebuttle was received
			for (Integer accuserId : this.receivedAccusations.keySet()) {
				final Set<Integer> accused = this.receivedAccusations.get(accuserId).getAccused();
				for (Integer defenderId : accused) {
					// Shareholder i, has accused shareholder j.
					// Look for a rebuttle from shareholder j for shareholder
					// i's claim

					// We haven't already accused j, we need to see if
					// shareholder_i or shareholder_j is lying

					// First, see if a rebuttle was given, if not we side with
					// the accuser
					final SimpleEntry<Integer, Integer> senderAccuserPair = new SimpleEntry<>(defenderId, accuserId);
					if (!this.receivedRebuttals.containsKey(senderAccuserPair)) {
						// No rebuttal, provided, side with the accuser
						this.getOurAccusations().add(defenderId);
					} else {
						// Rebuttle was given, see if it checks out
						final GenerationRebuttal rebuttal = this.receivedRebuttals.get(senderAccuserPair);

						try {

							// Get the accused's message
							final SemiPrivateMessage defendersMessage = (SemiPrivateMessage) this.receivedMessages
									.get(defenderId).getMessage();

							// Get accuser's encrypted payload
							final EncryptedPayload encryptedPayload = defendersMessage.getEncryptedPayload(accuserId);

							// Decrypt the encrypted message the accused sent to
							// the accuser
							final byte[] keyAndNonce = rebuttal.getRebuttalEvidence();

							final VssPrivatePayload privatePayload = (VssPrivatePayload) EciesEncryption.decryptPayload(
									encryptedPayload, keyAndNonce,
									this.shareholder.getOtherShareholderEncryptionPublicKey(accuserId));

							// Perform checks

							if ((privatePayload.getShareUpdate().getX().intValue() - 1) != accuserId) {
								// Invalid x coordinate of share
								throw new IllegalArgumentException("Invalid x value in share");
							}
							if (privatePayload.getShareUpdate().getY().compareTo(BigInteger.ZERO) < 0) {
								// Invalid y coordinate of share, it is
								// negative!
								throw new IllegalArgumentException("Negative y value in share");
							}
							if (privatePayload.getShareUpdate().getY().compareTo(r) >= 0) {
								// Invalid y coordinate of share, it is too big!
								throw new IllegalArgumentException("y value in share is too large");
							}

							// Perform feldman-based verification of our share
							// update!
							Shamir.verifyShamirShareConsistency(privatePayload.getShareUpdate(),
									((GenerationVssPublicPayload) defendersMessage.getPublicPayload())
											.getFeldmanValues());

							// Everything checks out, the accuser is lying
							System.err.println("Accusation is false, we are excluding [" + accuserId + "]");
							this.getOurAccusations().add(accuserId);

						} catch (Exception e) {
							// Something went wrong, side with the accuser
							System.err.println("Accusation is valid, we are excluding [" + defenderId + "]");
							this.getOurAccusations().add(defenderId);
						}
					}
				}
			}

			this.currentState = States.PROCESSED_REBUTTALS;

		} else {
			throw new IllegalStateException("Must be in States.MADE_ACCUSATIONS");
		}
	}

	public ShamirShare attemptShareGeneration(final PrivateKey decryptionKey) {
		if (this.currentState == States.PROCESSED_REBUTTALS) {

			// Filter all remaining messages based on our updated accusations
			for (final Integer corruptServer : this.getOurAccusations()) {
				this.receivedMessages.remove(corruptServer);
			}

			// Check if we are over update threshold
			if (this.receivedMessages.size() >= this.updateThreshold) {

				// Compute our updated share
				BigInteger updateSum = BigInteger.ZERO;
				for (final Integer sender : this.receivedMessages.keySet()) {
					// Decrypt our private share
					final SemiPrivateMessage message = (SemiPrivateMessage) this.receivedMessages.get(sender)
							.getMessage();
					final EncryptedPayload encryptedPayload = message.getEncryptedPayload(this.shareholder.getIndex());
					final VssPrivatePayload privatePayload = (VssPrivatePayload) EciesEncryption
							.decryptPayload(encryptedPayload, decryptionKey);

					updateSum = updateSum.add(privatePayload.getShareUpdate().getY()).mod(r);
				}

				this.currentState = States.PERFORMED_UPDATE;
				return new ShamirShare(BigInteger.valueOf(this.shareholder.getIndex() + 1), updateSum);

			} else {
				return null;
			}

		} else {
			throw new IllegalStateException("Must be in States.PROCESSED_REBUTTALS");
		}
	}

	public void computeSharePublicKeys(final EcPoint[] sharePublicKeys) {
		if (this.currentState == States.PERFORMED_UPDATE) {

			// Check if we are over update threshold
			if (this.receivedMessages.size() >= this.updateThreshold) {

				// Compute everyone else's updated share public key
				for (final Integer sender : this.receivedMessages.keySet()) {
					final SemiPrivateMessage message = (SemiPrivateMessage) this.receivedMessages.get(sender)
							.getMessage();

					// Get feldman values from message
					final GenerationVssPublicPayload vssPublicPayload = (GenerationVssPublicPayload) message
							.getPublicPayload();
					final EcPoint[] feldmanValues = vssPublicPayload.getFeldmanValues();

					// Compute updates for each shareholder form the feldman
					// values
					final EcPoint[] computedSharePublicKeys = Shamir.computeSharePublicKeys(feldmanValues, this.n);

					// Update each shareholder with the updates from this
					// message
					for (int i = 0; i < n; i++) {
						if (sharePublicKeys[i] == null) {
							sharePublicKeys[i] = EcPoint.pointAtInfinity;
						}
						sharePublicKeys[i] = curve.addPoints(sharePublicKeys[i], computedSharePublicKeys[i]);
					}


					this.secretPublicKey = Shamir.computeSharePublicKey(feldmanValues, 0);

				}
			}
		} else {
			throw new IllegalStateException("Must be in States.PERFORMED_UPDATE");
		}
	}

	public EcPoint getSecretPublicKey() {
		if (this.secretPublicKey == null) {
			throw new IllegalStateException("Must call computeSharePublicKeys first");
		} else {
			return this.secretPublicKey;
		}
	}

	public SortedSet<Integer> getOurAccusations() {
		return ourAccusations;
	}

}
