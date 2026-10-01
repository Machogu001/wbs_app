package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.annotation.RequiresPermission
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

object DocumentPrinter {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val executor = Executors.newSingleThreadExecutor()

    // Hands the PDF to Android's print framework, which discovers Wi-Fi/network (IPP/Mopria)
    // printers and any printer for which a vendor print-service plugin is installed.
    fun printWithSystem(context: Context, pdf: ByteArray, jobName: String) {
        val manager = context.getSystemService(PrintManager::class.java)
        manager.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("${jobName.replace(Regex("[^A-Za-z0-9_-]+"), "_")}.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal,
                callback: WriteResultCallback
            ) {
                try {
                    FileOutputStream(destination.fileDescriptor).use { it.write(pdf) }
                    if (cancellationSignal.isCanceled) callback.onWriteCancelled()
                    else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (error: Exception) {
                    callback.onWriteFailed(error.message)
                }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }

    // Renders every PDF page to a monochrome raster and streams it to a paired ESC/POS
    // Bluetooth thermal printer (58 mm = 384 dots, 80 mm = 576 dots wide).
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun printToBluetooth(
        cacheDir: File,
        device: BluetoothDevice,
        pdf: ByteArray,
        widthDots: Int,
        callback: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                val pages = renderPages(cacheDir, pdf, widthDots)
                openSocket(device).use { socket ->
                    val output = socket.outputStream
                    output.write(byteArrayOf(0x1B, 0x40))
                    pages.forEach { page ->
                        writeRaster(output, page)
                        page.recycle()
                    }
                    output.write(byteArrayOf(0x1B, 0x64, 4))
                    output.write(byteArrayOf(0x1D, 0x56, 66, 0))
                    output.flush()
                    // Give the printer time to drain its buffer before the link closes.
                    Thread.sleep(800)
                }
            })
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val secure = device.createRfcommSocketToServiceRecord(SPP_UUID)
        return try {
            secure.connect()
            secure
        } catch (_: Exception) {
            runCatching { secure.close() }
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
        }
    }

    private fun renderPages(cacheDir: File, pdf: ByteArray, widthDots: Int): List<Bitmap> {
        val file = File.createTempFile("print_", ".pdf", cacheDir)
        try {
            file.writeBytes(pdf)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    return (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            val renderWidth = widthDots * 2
                            val renderHeight = (renderWidth.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                            val full = createBitmap(renderWidth, renderHeight)
                            full.eraseColor(Color.WHITE)
                            page.render(full, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val cropped = cropToContent(full)
                            val height = (widthDots.toLong() * cropped.height / cropped.width).toInt().coerceAtLeast(1)
                            val scaled = cropped.scale(widthDots, height)
                            if (cropped !== full) cropped.recycle()
                            full.recycle()
                            scaled
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    // A4 documents have wide margins; trimming them keeps the text as large as possible
    // on narrow thermal paper.
    private fun cropToContent(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (isDark(pixels[row + x], 235)) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return bitmap
        val pad = width / 60
        left = (left - pad).coerceAtLeast(0)
        top = (top - pad).coerceAtLeast(0)
        right = (right + pad).coerceAtMost(width - 1)
        bottom = (bottom + pad).coerceAtMost(height - 1)
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    private fun isDark(color: Int, threshold: Int): Boolean {
        val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
        return luminance < threshold
    }

    private fun writeRaster(output: OutputStream, bitmap: Bitmap) {
        val width = bitmap.width
        val bytesPerRow = (width + 7) / 8
        val rowPixels = IntArray(width)
        val bandHeight = 128
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(bandHeight, bitmap.height - y)
            val data = ByteArray(bytesPerRow * rows)
            for (row in 0 until rows) {
                bitmap.getPixels(rowPixels, 0, width, 0, y + row, width, 1)
                for (x in 0 until width) {
                    if (isDark(rowPixels[x], 170)) {
                        val index = row * bytesPerRow + x / 8
                        data[index] = (data[index].toInt() or (0x80 shr (x % 8))).toByte()
                    }
                }
            }
            output.write(byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,
                (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8 and 0xFF).toByte(),
                (rows and 0xFF).toByte(), (rows shr 8 and 0xFF).toByte()
            ))
            output.write(data)
            output.flush()
            Thread.sleep(30)
            y += rows
        }
    }
}
