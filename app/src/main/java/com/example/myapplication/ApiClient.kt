package com.example.myapplication

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class ApiException(val statusCode: Int, message: String) : Exception(message)

data class DownloadedDocument(val file: File, val contentType: String)

class ApiClient(private val tokenProvider: () -> String?) {
    private val executor = Executors.newFixedThreadPool(3)
    private val baseUrl = "https://wbs.bremac.co.ke/api/mobile/"

    fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                val connection = open(path, method)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use {
                        it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                }
                readResponse(connection)
            })
        }
    }

    fun upload(
        path: String,
        fields: Map<String, String>,
        fileField: String,
        fileUri: Uri,
        resolver: ContentResolver,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                val boundary = "Wbs-${UUID.randomUUID()}"
                val connection = open(path, "POST").apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                connection.outputStream.buffered().use { output ->
                    fields.forEach { (name, value) ->
                        output.write("--$boundary\r\n".toByteArray())
                        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                        output.write(value.toByteArray(StandardCharsets.UTF_8))
                        output.write("\r\n".toByteArray())
                    }
                    val mimeType = resolver.getType(fileUri) ?: "image/jpeg"
                    output.write("--$boundary\r\n".toByteArray())
                    output.write(
                        "Content-Disposition: form-data; name=\"$fileField\"; filename=\"meter-photo\"\r\n"
                            .toByteArray()
                    )
                    output.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                    resolver.openInputStream(fileUri)?.use { it.copyTo(output) }
                        ?: error("The selected image could not be opened.")
                    output.write("\r\n--$boundary--\r\n".toByteArray())
                }
                readResponse(connection)
            })
        }
    }

    fun download(
        documentUrl: String,
        cacheDir: File,
        callback: (Result<DownloadedDocument>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                var currentUrl = URL(documentUrl)
                require(currentUrl.protocol.equals("https", ignoreCase = true)) {
                    "Only secure HTTPS documents can be opened."
                }
                var connection: HttpURLConnection? = null
                try {
                    var redirectCount = 0
                    while (true) {
                        connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 20_000
                            readTimeout = 30_000
                            setRequestProperty("Accept", "application/pdf,text/html,*/*")
                            if (currentUrl.host.equals(URL(baseUrl).host, ignoreCase = true)) {
                                tokenProvider()?.takeIf(String::isNotBlank)?.let {
                                    setRequestProperty("Authorization", "Bearer $it")
                                }
                            }
                        }
                        val status = connection.responseCode
                        if (status in 300..399) {
                            val location = connection.getHeaderField("Location")
                                ?: error("The document redirected without a destination.")
                            connection.disconnect()
                            redirectCount++
                            require(redirectCount <= 5) { "The document redirected too many times." }
                            currentUrl = URL(currentUrl, location)
                            require(currentUrl.protocol.equals("https", ignoreCase = true)) {
                                "Only secure HTTPS documents can be opened."
                            }
                            continue
                        }
                        if (status !in 200..299) {
                            val errorText = connection.errorStream?.use {
                                BufferedReader(InputStreamReader(it)).readText()
                            }.orEmpty()
                            val message = runCatching {
                                org.json.JSONObject(errorText).optString("message")
                            }.getOrNull().orEmpty().ifBlank { "Could not load document ($status)." }
                            throw ApiException(status, message)
                        }
                        val file = File.createTempFile("wbs-document-", ".cache", cacheDir)
                        try {
                            connection.inputStream.use { input ->
                                FileOutputStream(file).use { output -> input.copyTo(output) }
                            }
                        } catch (error: Exception) {
                            file.delete()
                            throw error
                        }
                        return@runCatching DownloadedDocument(
                            file,
                            connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                        )
                    }
                    error("Could not load document.")
                } finally {
                    connection?.disconnect()
                }
            })
        }
    }

    fun query(path: String, params: Map<String, String>): String {
        val query = params.filterValues(String::isNotBlank).entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        return if (query.isBlank()) path else "$path?$query"
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            tokenProvider()?.takeIf(String::isNotBlank)?.let {
                setRequestProperty("Authorization", "Bearer " + it)
            }
        }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
        connection.disconnect()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (statusCode !in 200..299) {
            throw ApiException(statusCode, json.optString("message", "Request failed ($statusCode)"))
        }
        if (json.optString("status").equals("error", ignoreCase = true)) {
            throw ApiException(statusCode, json.optString("message", "The server could not process the request."))
        }
        return json
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
