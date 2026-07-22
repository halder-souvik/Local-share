package com.example.server

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.util.*

class LocalHttpServer(
    private val context: Context,
    private val rootUriString: String,
    private val port: Int,
    private val onBytesTransferred: (Long) -> Unit
) {
    companion object {
        val activePeers: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf<String, Long>())
    }

    private val tag = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private var serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        serverScope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d(tag, "Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Server exception: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket: ${e.message}")
        }
        serverScope.cancel()
        serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
            // Read HTTP request line
            val requestLine = readLine(input) ?: return@withContext
            Log.d(tag, "Request: $requestLine")
            val tokens = requestLine.split(" ")
            if (tokens.size < 2) return@withContext
            val method = tokens[0]
            val fullPath = tokens[1]

            val clientIp = socket.inetAddress?.hostAddress
            if (clientIp != null && clientIp != "127.0.0.1") {
                activePeers[clientIp] = System.currentTimeMillis()
            }

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(":")
                if (colonIdx != -1) {
                    val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            val rootUri = Uri.parse(rootUriString)
            val rootFolder = DocumentFile.fromTreeUri(context, rootUri)
            if (rootFolder == null) {
                sendError(output, 500, "Internal Server Error: Shared folder not found.")
                return@withContext
            }

            // Split path and query parameters
            val pathAndQuery = fullPath.split("?", limit = 2)
            val path = URLDecoder.decode(pathAndQuery[0], "UTF-8")
            val queryParams = if (pathAndQuery.size > 1) {
                parseQueryParams(pathAndQuery[1])
            } else {
                emptyMap()
            }

            if (method.equals("GET", ignoreCase = true)) {
                handleGet(path, queryParams, rootFolder, output)
            } else if (method.equals("POST", ignoreCase = true)) {
                handlePost(path, queryParams, headers, input, rootFolder, output)
            } else {
                sendError(output, 405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling client: ${e.message}", e)
            try {
                sendError(output, 500, "Internal Server Error: ${e.message}")
            } catch (ignored: Exception) {}
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun handleGet(
        path: String,
        queryParams: Map<String, String>,
        rootFolder: DocumentFile,
        output: BufferedOutputStream
    ) {
        // Special route for file deletion
        if (path == "/delete") {
            val fileRelativePath = queryParams["path"] ?: "/"
            val fileDoc = resolveDocumentFile(rootFolder, fileRelativePath)
            if (fileDoc != null && fileDoc.exists()) {
                val parentPath = fileRelativePath.substringBeforeLast("/", "/")
                val deleted = fileDoc.delete()
                if (deleted) {
                    sendRedirect(output, parentPath)
                } else {
                    sendError(output, 500, "Failed to delete file.")
                }
            } else {
                sendError(output, 404, "File Not Found")
            }
            return
        }

        val targetDoc = resolveDocumentFile(rootFolder, path)
        if (targetDoc == null || !targetDoc.exists()) {
            sendError(output, 404, "Not Found")
            return
        }

        if (targetDoc.isDirectory) {
            serveDirectory(targetDoc, path, output)
        } else {
            serveFile(targetDoc, output)
        }
    }

    private fun handlePost(
        path: String,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
        input: BufferedInputStream,
        rootFolder: DocumentFile,
        output: BufferedOutputStream
    ) {
        when (path) {
            "/upload" -> {
                val destPath = queryParams["path"] ?: "/"
                val destDir = resolveDocumentFile(rootFolder, destPath)
                if (destDir == null || !destDir.isDirectory) {
                    sendError(output, 400, "Invalid Destination Directory")
                    return
                }

                val contentType = headers["content-type"] ?: ""
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

                if (!contentType.contains("multipart/form-data")) {
                    sendError(output, 400, "Unsupported media type. Multipart form-data expected.")
                    return
                }

                val boundaryMatch = Regex("boundary=(.+)").find(contentType)
                if (boundaryMatch == null) {
                    sendError(output, 400, "Boundary not found in Content-Type header.")
                    return
                }
                val boundary = "--" + boundaryMatch.groupValues[1]

                // Parse multipart
                try {
                    val multipartParser = MultipartStreamParser(input, boundary, contentLength)
                    var fileCreated = false
                    while (true) {
                        val partHeaders = multipartParser.readNextPartHeaders() ?: break
                        val contentDisposition = partHeaders["content-disposition"] ?: ""
                        val filenameMatch = Regex("filename=\"([^\"]+)\"").find(contentDisposition)

                        if (filenameMatch != null) {
                            val originalFilename = filenameMatch.groupValues[1]
                            // Sanitize filename
                            val filename = originalFilename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            if (filename.isNotEmpty()) {
                                val existingFile = destDir.findFile(filename)
                                existingFile?.delete() // Overwrite if exists

                                val mimeType = partHeaders["content-type"] ?: "application/octet-stream"
                                val newFile = destDir.createFile(mimeType, filename)
                                if (newFile != null) {
                                    context.contentResolver.openOutputStream(newFile.uri)?.use { fileOut ->
                                        multipartParser.readPartBodyToStream(fileOut, onBytesTransferred)
                                    }
                                    fileCreated = true
                                }
                            }
                        } else {
                            // Read text form fields (discard or handle as needed)
                            multipartParser.discardPartBody()
                        }
                    }

                    if (fileCreated) {
                        sendRedirect(output, destPath)
                    } else {
                        sendError(output, 400, "No valid file uploaded.")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Multipart parse error", e)
                    sendError(output, 500, "Upload error: ${e.message}")
                }
            }
            "/create_folder" -> {
                val parentPath = queryParams["path"] ?: "/"
                val parentDir = resolveDocumentFile(rootFolder, parentPath)
                if (parentDir == null || !parentDir.isDirectory) {
                    sendError(output, 400, "Invalid Parent Directory")
                    return
                }

                // Parse form-url-encoded body to get directory name
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val bodyBytes = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = input.read(bodyBytes, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    val bodyStr = String(bodyBytes, Charsets.UTF_8)
                    val bodyParams = parseQueryParams(bodyStr)
                    val folderName = bodyParams["folder_name"]?.trim()?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: ""
                    if (folderName.isNotEmpty()) {
                        val created = parentDir.createDirectory(folderName)
                        if (created != null) {
                            sendRedirect(output, parentPath)
                            return
                        }
                    }
                }
                sendError(output, 400, "Failed to create directory. Ensure a valid name is provided.")
            }
            else -> {
                sendError(output, 404, "Not Found")
            }
        }
    }

    private fun serveDirectory(dir: DocumentFile, path: String, output: BufferedOutputStream) {
        val filesList = dir.listFiles() ?: emptyArray()
        // Sort directories first, then files
        val sortedFiles = filesList.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase(Locale.ROOT) }))

        val html = buildString {
            append("<!DOCTYPE html>")
            append("<html lang=\"en\" class=\"h-full\">")
            append("<head>")
            append("<meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            append("<title>LocalShare - ${escapeHtml(dir.name ?: "Shared Folder")}</title>")
            append("<script src=\"https://cdn.tailwindcss.com\"></script>")
            append("<link href=\"https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">")
            append("<style>")
            append("body { font-family: 'Plus Jakarta Sans', sans-serif; }")
            append("</style>")
            append("<script>")
            append("""
                tailwind.config = {
                    theme: {
                        extend: {
                            colors: {
                                brand: {
                                    bg: '#FDF8F6',
                                    text: '#1C1B1F',
                                    primary: '#6750A4',
                                    container: '#EADDFF',
                                    light: '#D0BCFF',
                                    deepText: '#21005D',
                                    surface: '#F3EDF7',
                                    border: '#CAC4D0'
                                }
                            }
                        }
                    }
                }
            """.trimIndent())
            append("</script>")
            append("</head>")
            append("<body class=\"bg-brand-bg text-brand-text min-h-full flex flex-col\">")

            // Header Section
            append("<header class=\"bg-brand-surface border-b border-brand-border py-4 px-6 sticky top-0 z-50\">")
            append("<div class=\"max-w-6xl mx-auto flex flex-col md:flex-row md:items-center justify-between gap-4\">")
            append("<div class=\"flex items-center gap-3\">")
            append("<div class=\"w-10 h-10 bg-brand-primary rounded-xl flex items-center justify-center text-white font-bold text-lg shadow-sm\">LS</div>")
            append("<div>")
            append("<h1 class=\"text-xl font-bold tracking-tight text-brand-deepText\">LocalShare Server</h1>")
            append("<p class=\"text-xs text-brand-primary/80 font-medium\">Secure local network file system</p>")
            append("</div>")
            append("</div>")
            append("<div class=\"bg-brand-container px-4 py-2 rounded-2xl flex items-center gap-2 border border-brand-light/30\">")
            append("<span class=\"w-2.5 h-2.5 rounded-full bg-brand-primary animate-pulse\"></span>")
            append("<span class=\"text-xs font-semibold text-brand-deepText font-mono\">Connected as Peer</span>")
            append("</div>")
            append("</div>")
            append("</header>")

            append("<main class=\"flex-1 max-w-6xl w-full mx-auto p-4 md:p-6 flex flex-col gap-6\">")

            // Breadcrumbs Bar
            append("<nav class=\"flex flex-wrap items-center gap-1.5 text-sm font-medium px-1 py-1 bg-brand-surface rounded-2xl px-4 py-3 border border-brand-border/40 shadow-sm\">")
            append("<a href=\"/\" class=\"text-brand-primary hover:underline flex items-center gap-1\">")
            append("<svg class=\"w-4 h-4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6\"/></svg>")
            append("Root</a>")

            if (path != "/") {
                val segments = path.trim('/').split('/')
                var accumPath = ""
                for (segment in segments) {
                    accumPath += "/$segment"
                    append("<span class=\"text-brand-border\">/</span>")
                    append("<a href=\"${URLEncoder.encode(accumPath, "UTF-8")}\" class=\"text-brand-primary hover:underline\">${escapeHtml(segment)}</a>")
                }
            }
            append("</nav>")

            // Upload & Actions Panels (Grid)
            append("<div class=\"grid grid-cols-1 md:grid-cols-2 gap-6\">")

            // File Upload Card
            append("<section class=\"bg-white border border-brand-border/60 rounded-3xl p-6 shadow-sm flex flex-col gap-4\">")
            append("<div class=\"flex items-center gap-2\">")
            append("<svg class=\"w-5 h-5 text-brand-primary\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12\"/></svg>")
            append("<h2 class=\"text-base font-bold text-brand-deepText\">Upload File</h2>")
            append("</div>")
            append("<form action=\"/upload?path=${URLEncoder.encode(path, "UTF-8")}\" method=\"post\" enctype=\"multipart/form-data\" class=\"flex flex-col gap-3\">")
            append("<div class=\"border-2 border-dashed border-brand-border/60 hover:border-brand-primary/50 transition-colors rounded-2xl p-6 text-center flex flex-col items-center justify-center cursor-pointer relative\">")
            append("<input type=\"file\" name=\"file\" required class=\"absolute inset-0 opacity-0 cursor-pointer\" id=\"file-input\" onchange=\"updateFileName()\">")
            append("<svg class=\"w-10 h-10 text-brand-border mb-2\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M12 4.5v15m7.5-7.5h-15\"/></svg>")
            append("<p class=\"text-sm font-semibold text-brand-text\" id=\"file-label\">Drag and drop or click to choose file</p>")
            append("<p class=\"text-xs text-brand-border mt-1\">Any local file format supported</p>")
            append("</div>")
            append("<button type=\"submit\" class=\"bg-brand-primary text-white font-semibold py-3 px-4 rounded-2xl shadow-sm hover:opacity-90 transition-all flex items-center justify-center gap-2\">")
            append("<svg class=\"w-4 h-4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M4.5 10.5L12 3m0 0l7.5 7.5M12 3v18\"/></svg>")
            append("Upload to Current Folder</button>")
            append("</form>")
            append("</section>")

            // Create Directory Card
            append("<section class=\"bg-white border border-brand-border/60 rounded-3xl p-6 shadow-sm flex flex-col justify-between gap-4\">")
            append("<div>")
            append("<div class=\"flex items-center gap-2 mb-4\">")
            append("<svg class=\"w-5 h-5 text-brand-primary\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M12 10.5v6m3-3H9m4.06-7.19l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z\"/></svg>")
            append("<h2 class=\"text-base font-bold text-brand-deepText\">Create New Folder</h2>")
            append("</div>")
            append("<form action=\"/create_folder?path=${URLEncoder.encode(path, "UTF-8")}\" method=\"post\" class=\"flex flex-col gap-3\">")
            append("<div class=\"flex flex-col gap-1\">")
            append("<label for=\"folder_name\" class=\"text-xs font-semibold text-brand-text/70 px-1\">Folder Name</label>")
            append("<input type=\"text\" name=\"folder_name\" id=\"folder_name\" placeholder=\"Enter folder name\" required class=\"bg-brand-surface border border-brand-border/80 focus:border-brand-primary outline-none px-4 py-3 rounded-2xl text-sm\">")
            append("</div>")
            append("<button type=\"submit\" class=\"bg-brand-container text-brand-deepText font-bold py-3 px-4 rounded-2xl hover:opacity-90 transition-all flex items-center justify-center gap-2 mt-2\">")
            append("<svg class=\"w-4 h-4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M12 4.5v15m7.5-7.5h-15\"/></svg>")
            append("Create Folder</button>")
            append("</form>")
            append("</div>")
            append("</section>")

            append("</div>")

            // Directories/Files List Card
            append("<section class=\"bg-white border border-brand-border/60 rounded-3xl p-6 shadow-sm flex flex-col gap-4\">")
            append("<div class=\"flex items-center justify-between flex-wrap gap-2\">")
            append("<div class=\"flex items-center gap-2\">")
            append("<svg class=\"w-5 h-5 text-brand-primary\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M2.25 12.75V12A2.25 2.25 0 014.5 9.75h15A2.25 2.25 0 0121.75 12v.75m-8.69-6.44l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z\"/></svg>")
            append("<h2 class=\"text-base font-bold text-brand-deepText\">Folder Contents (${sortedFiles.size})</h2>")
            append("</div>")
            append("<input type=\"text\" id=\"search-input\" placeholder=\"Filter files...\" oninput=\"filterFiles()\" class=\"bg-brand-surface border border-brand-border/60 outline-none px-4 py-2 rounded-xl text-xs w-full sm:w-64 focus:border-brand-primary\">")
            append("</div>")

            append("<div class=\"overflow-x-auto\">")
            append("<table class=\"w-full text-left border-collapse\">")
            append("<thead>")
            append("<tr class=\"border-b border-brand-border/40 text-brand-text/60 text-xs font-semibold uppercase tracking-wider\">")
            append("<th class=\"py-3 px-4\">Name</th>")
            append("<th class=\"py-3 px-4 hidden md:table-cell\">Size</th>")
            append("<th class=\"py-3 px-4 hidden sm:table-cell\">Modified</th>")
            append("<th class=\"py-3 px-4 text-right\">Actions</th>")
            append("</tr>")
            append("</thead>")
            append("<tbody id=\"files-tbody\">")

            if (sortedFiles.isEmpty()) {
                append("<tr class=\"file-row\"><td colspan=\"4\" class=\"py-8 text-center text-sm text-brand-text/50\">This folder is empty. Upload files or create a subfolder above!</td></tr>")
            } else {
                for (file in sortedFiles) {
                    val name = file.name ?: "Unnamed"
                    val isDir = file.isDirectory
                    val relPath = if (path == "/") "/$name" else "$path/$name"
                    val encRelPath = URLEncoder.encode(relPath, "UTF-8")

                    append("<tr class=\"file-row border-b border-brand-border/30 hover:bg-brand-surface/40 transition-colors\">")
                    
                    // Name + Icon
                    append("<td class=\"py-4 px-4 flex items-center gap-3\">")
                    if (isDir) {
                        append("<span class=\"text-brand-primary\">")
                        append("<svg class=\"w-5 h-5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M2.25 12.75V12A2.25 2.25 0 014.5 9.75h15A2.25 2.25 0 0121.75 12v.75m-8.69-6.44l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z\"/></svg>")
                        append("</span>")
                        append("<a href=\"$encRelPath\" class=\"font-semibold text-brand-deepText hover:underline hover:text-brand-primary truncate max-w-[200px] sm:max-w-[400px]\">${escapeHtml(name)}</a>")
                    } else {
                        append("<span class=\"text-brand-text/60\">")
                        append("<svg class=\"w-5 h-5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z\"/></svg>")
                        append("</span>")
                        append("<a href=\"$encRelPath\" class=\"text-brand-text hover:underline hover:text-brand-primary truncate max-w-[200px] sm:max-w-[400px]\">${escapeHtml(name)}</a>")
                    }
                    append("</td>")

                    // Size
                    append("<td class=\"py-4 px-4 text-sm text-brand-text/80 hidden md:table-cell\">")
                    if (isDir) {
                        append("-")
                    } else {
                        append(humanReadableByteCountBin(file.length()))
                    }
                    append("</td>")

                    // Date Modified
                    append("<td class=\"py-4 px-4 text-xs text-brand-text/60 hidden sm:table-cell\">")
                    val lastModified = file.lastModified()
                    if (lastModified > 0) {
                        append(Date(lastModified).toString())
                    } else {
                        append("-")
                    }
                    append("</td>")

                    // Actions
                    append("<td class=\"py-4 px-4 text-right\">")
                    append("<div class=\"flex items-center justify-end gap-2\">")
                    if (!isDir) {
                        append("<a href=\"$encRelPath\" download=\"${escapeHtml(name)}\" class=\"inline-flex items-center justify-center w-8 h-8 rounded-lg bg-brand-surface text-brand-primary hover:bg-brand-container transition-colors\" title=\"Download file\">")
                        append("<svg class=\"w-4 h-4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3\"/></svg>")
                        append("</a>")
                    } else {
                        append("<a href=\"$encRelPath\" class=\"inline-flex items-center justify-center w-8 h-8 rounded-lg bg-brand-surface text-brand-primary hover:bg-brand-container transition-colors\" title=\"Open folder\">")
                        append("<svg class=\"w-4 h-4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M4.5 12h15m0 0l-6.75-6.75M19.5 12l-6.75 6.75\"/></svg>")
                        append("</a>")
                    }
                    // Delete button
                    append("<a href=\"/delete?path=${URLEncoder.encode(relPath, "UTF-8")}\" onclick=\"return confirm('Are you sure you want to delete this ${if (isDir) "folder" else "file"}?')\" class=\"inline-flex items-center justify-center w-8 h-8 rounded-lg bg-brand-surface text-red-600 hover:bg-red-50 transition-colors\" title=\"Delete\">")
                    append("<svg class=\"w-4 h-4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0\"/></svg>")
                    append("</a>")
                    append("</div>")
                    append("</td>")
                    
                    append("</tr>")
                }
            }

            append("</tbody>")
            append("</table>")
            append("</div>")
            append("</section>")

            append("</main>")

            append("<footer class=\"border-t border-brand-border/40 py-6 text-center text-xs text-brand-text/50 mt-auto\">")
            append("Powered by <strong>LocalShare</strong> on Android. Ensure devices remain on the same Wi-Fi network.")
            append("</footer>")

            // Javascript for file labels and client filtering
            append("<script>")
            append("""
                function updateFileName() {
                    const input = document.getElementById('file-input');
                    const label = document.getElementById('file-label');
                    if (input.files && input.files.length > 0) {
                        label.textContent = input.files[0].name;
                    } else {
                        label.textContent = "Drag and drop or click to choose file";
                    }
                }
                function filterFiles() {
                    const searchInput = document.getElementById('search-input').value.toLowerCase();
                    const rows = document.querySelectorAll('#files-tbody .file-row');
                    rows.forEach(row => {
                        const firstCell = row.querySelector('td');
                        if (firstCell) {
                            const text = firstCell.textContent.toLowerCase();
                            if (text.includes(searchInput)) {
                                row.style.display = '';
                            } else {
                                row.style.display = 'none';
                            }
                        }
                    });
                }
            """.trimIndent())
            append("</script>")

            append("</body>")
            append("</html>")
        }

        sendHtml(output, html)
    }

    private fun serveFile(file: DocumentFile, output: BufferedOutputStream) {
        val inputStream = context.contentResolver.openInputStream(file.uri)
        if (inputStream == null) {
            sendError(output, 500, "Unable to read file content.")
            return
        }

        val size = file.length()
        val mimeType = file.type ?: "application/octet-stream"

        try {
            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
            output.write("Content-Type: $mimeType\r\n".toByteArray())
            output.write("Content-Length: $size\r\n".toByteArray())
            output.write("Content-Disposition: attachment; filename=\"${file.name}\"\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.flush()

            val buffer = ByteArray(64 * 1024) // 64KB chunks
            var bytesRead: Int
            inputStream.use { stream ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    onBytesTransferred(bytesRead.toLong())
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(tag, "Error writing file payload", e)
        }
    }

    private fun resolveDocumentFile(rootFolder: DocumentFile, relativePath: String): DocumentFile? {
        val cleanPath = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        var current: DocumentFile = rootFolder
        for (segment in cleanPath) {
            val decodedSegment = URLDecoder.decode(segment, "UTF-8")
            val next = current.findFile(decodedSegment) ?: return null
            current = next
        }
        return current
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                params[key] = value
            } else if (pair.isNotEmpty()) {
                val key = URLDecoder.decode(pair, "UTF-8")
                params[key] = ""
            }
        }
        return params
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) {
                break
            }
            if (c != '\r'.code) {
                sb.append(c.toChar())
            }
        }
        if (c == -1 && sb.isEmpty()) return null
        return sb.toString()
    }

    private fun sendHtml(output: BufferedOutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun sendRedirect(output: BufferedOutputStream, path: String) {
        val encodedPath = URLEncoder.encode(path, "UTF-8")
        val response = "HTTP/1.1 303 See Other\r\nLocation: $encodedPath\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun sendError(output: BufferedOutputStream, code: Int, message: String) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Error - $code</title></head>
            <body style="font-family:sans-serif; text-align:center; padding: 100px; background:#FDF8F6; color:#1C1B1F;">
                <h1 style="color:#6750A4;">Error $code</h1>
                <p>$message</p>
                <a href="/" style="color:#6750A4; font-weight:bold; text-decoration:none;">Go back to Safety</a>
            </body>
            </html>
        """.trimIndent()
        val bytes = html.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code $message\r\n".toByteArray())
        output.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    private fun humanReadableByteCountBin(bytes: Long): String {
        val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(bytes)
        if (absB < 1024) {
            return "$bytes B"
        }
        var value = absB
        val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
        var i = 40
        while (i >= 0 && absB > 0xfffccccccccL shr i) {
            value = value shr 10
            ci.next()
            i -= 10
        }
        value *= java.lang.Long.signum(bytes).toLong()
        return String.format("%.1f %ciB", value / 1024.0, ci.current())
    }
}
