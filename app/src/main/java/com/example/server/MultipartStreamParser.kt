package com.example.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MultipartStreamParser(
    private val input: BufferedInputStream,
    private val boundary: String,
    private val totalLength: Long
) {
    private val boundaryBytes = boundary.toByteArray(Charsets.US_ASCII)
    private val boundaryLen = boundaryBytes.size
    private var hasReachedEnd = false

    // Reads headers for the next part (ending with empty line)
    fun readNextPartHeaders(): Map<String, String>? {
        if (hasReachedEnd) return null

        val headers = mutableMapOf<String, String>()
        // Read line by line until an empty line
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) {
                break
            }
            // Check if this line is a boundary line itself or starting boundary
            if (line.contains(boundary)) {
                if (line.endsWith("--")) {
                    hasReachedEnd = true
                    return null
                }
                continue // Skip boundary line to get to actual headers
            }

            val colonIdx = line.indexOf(":")
            if (colonIdx != -1) {
                val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }
        return if (headers.isEmpty() && hasReachedEnd) null else headers
    }

    // Reads the body of the current part and writes it to outStream.
    // Stops exactly before the boundary string.
    fun readPartBodyToStream(outStream: OutputStream, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(1024 * 8)
        var bufferLen = 0

        // We use a simple state-based scanner to find the boundary bytes: "\r\n" + boundary
        val delimiter = ("\r\n" + boundary).toByteArray(Charsets.US_ASCII)
        val delimLen = delimiter.size

        while (true) {
            // Fill the buffer
            val read = input.read(buffer, bufferLen, buffer.size - bufferLen)
            if (read == -1) {
                // End of stream
                if (bufferLen > 0) {
                    outStream.write(buffer, 0, bufferLen)
                    onProgress(bufferLen.toLong())
                }
                hasReachedEnd = true
                break
            }
            bufferLen += read

            // Search for delimiter in the buffer
            val delimIdx = indexOf(buffer, bufferLen, delimiter)
            if (delimIdx != -1) {
                // Found boundary! Write everything up to the boundary, and slide buffer
                if (delimIdx > 0) {
                    outStream.write(buffer, 0, delimIdx)
                    onProgress(delimIdx.toLong())
                }

                // Check if it's the final boundary ("--")
                val afterDelimIdx = delimIdx + delimLen
                val isFinal = (afterDelimIdx + 1 < bufferLen && 
                               buffer[afterDelimIdx] == '-'.code.toByte() && 
                               buffer[afterDelimIdx + 1] == '-'.code.toByte())
                if (isFinal) {
                    hasReachedEnd = true
                }

                // Slide the buffer past the delimiter
                val remain = bufferLen - afterDelimIdx
                System.arraycopy(buffer, afterDelimIdx, buffer, 0, remain)
                bufferLen = remain

                // Skip any trailing \r\n
                if (bufferLen >= 2 && buffer[0] == '\r'.code.toByte() && buffer[1] == '\n'.code.toByte()) {
                    System.arraycopy(buffer, 2, buffer, 0, bufferLen - 2)
                    bufferLen -= 2
                }
                break
            } else {
                // Not found. We can safely write all but the last delimLen bytes
                val safeWriteLen = bufferLen - delimLen
                if (safeWriteLen > 0) {
                    outStream.write(buffer, 0, safeWriteLen)
                    onProgress(safeWriteLen.toLong())
                    System.arraycopy(buffer, safeWriteLen, buffer, 0, delimLen)
                    bufferLen = delimLen
                }
            }
        }
    }

    // Discard the current body
    fun discardPartBody() {
        val nullStream = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        readPartBodyToStream(nullStream, {})
    }

    private fun readLine(): String? {
        val bos = ByteArrayOutputStream()
        var c: Int
        while (true) {
            c = input.read()
            if (c == -1) {
                if (bos.size() == 0) return null
                break
            }
            if (c == '\n'.code) {
                break
            }
            if (c != '\r'.code) {
                bos.write(c)
            }
        }
        return bos.toString("UTF-8")
    }

    private fun indexOf(source: ByteArray, sourceLen: Int, target: ByteArray): Int {
        if (target.isEmpty()) return 0
        val first = target[0]
        val max = sourceLen - target.size
        for (i in 0..max) {
            if (source[i] != first) {
                continue
            }
            var matches = true
            for (j in 1 until target.size) {
                if (source[i + j] != target[j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return -1
    }
}
