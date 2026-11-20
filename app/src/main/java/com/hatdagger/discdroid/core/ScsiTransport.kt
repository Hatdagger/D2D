package com.hatdagger.discdroid.core

import java.io.Closeable
import java.io.IOException

/** Direction of the data phase of a SCSI command. */
enum class Direction { NONE, IN, OUT }

/**
 * Result of one SCSI command at the transport level.
 * [status] is the SCSI status from the drive: 0 = GOOD, 1 = CHECK CONDITION.
 * [transferred] is how many data bytes actually moved.
 */
class CommandResult(val status: Int, val transferred: Int)

/**
 * Anything that can carry SCSI command blocks to a drive.
 * On Android this is USB Bulk-Only Transport; in tests it is a fake drive.
 * Transport-level failures (cable pulled, protocol desync) throw [IOException].
 */
interface ScsiTransport : Closeable {
    val description: String

    fun execute(
        cdb: ByteArray,
        direction: Direction,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): CommandResult
}

/** Thrown when the drive reports CHECK CONDITION; carries the decoded sense data. */
class ScsiException(val sense: Sense, val command: String) :
    IOException("$command failed: ${sense.describe()}")
