package io.maksymdobrynin.snowflakegenerator

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class Generator(
	// 00:00:00 on January 1st, 2000 (UTC)
	private val startingEpoch: Long = 946684800L,
	private val nextTimeSeed: () -> Long = { System.currentTimeMillis() },
	private val datacenterId: Long,
	private val workedId: Long,
	private var sequence: Long = 0L,
) {
	companion object {
		private const val NEW_TIMESTAMP_TIMEOUT = 3000L
		private const val NEW_TIMESTAMP_DELAY = 50L
		private const val DATACENTER_BITS = 5
		private const val WORKER_BITS = 5
		private const val SEQUENCE_BITS = 12
	}

	/**
	 * Maximum possible to be stored in 5-bits as datacenter id.
	 * 0b11111 (binary) == 31 (decimal) == 2 ^ 5 - 1
	 */
	private val maxDatacenterId = (1L shl DATACENTER_BITS) - 1

	/**
	 * Maximum possible to be stored in 5-bits as worker id.
	 * 0b11111 (binary) == 31 (decimal) == 2 ^ 5 - 1
	 */
	private val maxWorkerId = (1L shl WORKER_BITS) - 1

	/**
	 * Maximum possible to be stored in 12-bits as sequence.
	 * 0b111111111111 (binary) == 4095 (decimal) == 2 ^ 12 - 1
	 */
	private val maxSequence = (1L shl SEQUENCE_BITS) - 1

	private val workerIdShift = SEQUENCE_BITS
	private val datacenterIdShift = SEQUENCE_BITS + WORKER_BITS
	private val timestampIdShift = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS

	/**
	 * For making sure exclusive access in between different threads,
	 * while generating IDs and using the same instance of [lastTimestamp].
	 */
	private val lock = Mutex()
	private var lastTimestamp = 0L

	init {
		require(startingEpoch >= 0 && startingEpoch <= Long.MAX_VALUE) {
			"Starting time epoch must match range 0 .. ${Long.MAX_VALUE}"
		}
		require(datacenterId > 0 && datacenterId <= maxDatacenterId) {
			"Datacenter ID must match range 1 .. $maxDatacenterId"
		}
		require(workedId > 0 && workedId <= maxWorkerId) {
			"Worker ID must match range 1 .. $maxWorkerId"
		}
		require(sequence >= 0 && sequence <= maxSequence) {
			"Sequence must match range 0 .. $maxSequence"
		}
	}

	/**
	 * Generates the next unique identifier in a thread-safe manner.
	 *
	 * This method ensures that the generated ID is unique by using a combination of
	 * the current timestamp, datacenter ID, worker ID, and a sequence number.
	 * The generated ID is a 64-bit unsigned number, where each component is packed
	 * into specific bit segments to ensure uniqueness.
	 *
	 * It uses a `Mutex` to synchronize access and prevent race conditions.
	 *
	 * @return The next unique identifier as a 64-bit unsigned `Long`.
	 */
	suspend fun nextId(): Long =
		lock.withLock {
			var timestamp = nextTimeSeed.invoke()

			if (lastTimestamp == timestamp) {
				sequence = (sequence + 1) and maxSequence
				if (sequence == 0L) {
					timestamp = wait()
				}
			} else {
				sequence = 0
			}

			lastTimestamp = timestamp

			return ((lastTimestamp - startingEpoch) shl timestampIdShift) or
				(datacenterId shl datacenterIdShift) or
				(workedId shl workerIdShift) or
				sequence
		}

	private suspend fun wait(): Long =
		withTimeout(NEW_TIMESTAMP_TIMEOUT) {
			var curr = nextTimeSeed.invoke()
			while (curr <= lastTimestamp) {
				delay(NEW_TIMESTAMP_DELAY)
				curr = nextTimeSeed.invoke()
			}
			curr
		}
}
