package com.example.quickdrop

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

class WebServer(
    private val port: Int,
    private val contentResolver: ContentResolver,
    private val sharedUris: List<Uri>?,
    private val rootPath: File?,
    private val isPrivate: Boolean
) : Thread() {

    private var serverSocket: ServerSocket? = null
    var isRunning = false

    override fun run() {
        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            while (isRunning) {
                val socket = serverSocket?.accept() ?: break
                handleClient(socket)
            }
        } catch (e: IOException) { e.printStackTrace() }
    }

    fun stopServer() {
        isRunning = false
        try { serverSocket?.close() } catch (e: IOException) { e.printStackTrace() }
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintStream(socket.getOutputStream())
                val requestLine = input.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread

                val method = parts[0]
                val path = parts[1]

                if (method == "GET") {
                    when {
                        path == "/" || path == "/files" -> serveHtml(output)
                        path == "/api/info" -> serveInfo(output)
                        path.startsWith("/api/files") -> {
                            val decoded = URLDecoder.decode(path, "UTF-8")
                            val subPath = if (decoded.length > 10) decoded.substring(11) else ""

                            if (path.contains("__storage__")) {
                                val cleanPath = subPath.replace("__storage__", "")
                                // Fix: Pass forceStorage parameter
                                serveJsonList(output, cleanPath, forceStorage = true)
                            } else {
                                // Fix: Pass forceStorage parameter
                                serveJsonList(output, subPath, forceStorage = false)
                            }
                        }
                        path.startsWith("/download/") -> serveFile(output, path)
                        else -> send404(output)
                    }
                }
                output.close()
                input.close()
                socket.close()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    private fun serveInfo(output: PrintStream) {
        val isSharedMode = sharedUris != null && sharedUris.isNotEmpty()
        val rootName = if (isSharedMode) "Shared Files" else "Internal Storage"
        val json = JSONObject()
        json.put("root_folder_name", rootName)
        json.put("is_shared_mode", isSharedMode)
        json.put("is_private", isPrivate)
        sendResponse(output, "200 OK", "application/json", json.toString().toByteArray())
    }

    private fun serveHtml(output: PrintStream) {
        val html = """
<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>QuickDrop</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css">
    <style>
        body { font-family: 'Inter', sans-serif; background-color: #111827; color: #e5e7eb; }
        .hidden-force { display: none !important; }
    </style>
</head>
<body class="p-2 sm:p-4 md:p-8">
    <div class="max-w-7xl mx-auto">
        <header class="flex flex-col md:flex-row justify-between items-center mb-6 gap-4">
            <div class="flex items-center gap-3 self-start md:self-center">
                <i class="fas fa-bolt-lightning text-3xl text-indigo-400"></i>
                <h1 class="text-2xl sm:text-3xl font-bold" id="page-title">QuickDrop</h1>
            </div>
            <div id="search-container" class="w-full md:w-1/3">
                <div class="relative">
                    <span class="absolute inset-y-0 left-0 flex items-center pl-3"><i class="fas fa-search text-gray-400"></i></span>
                    <input type="search" id="search-box" placeholder="Search files..." class="w-full pl-10 pr-4 py-2 rounded-lg bg-gray-700 border border-gray-600 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                </div>
            </div>
        </header>

        <div id="shared-banner" class="hidden-force mb-6 bg-indigo-900/40 border border-indigo-500 rounded-lg p-8 text-center shadow-xl">
            <i class="fas fa-file-arrow-down text-5xl text-indigo-400 mb-4 animate-bounce"></i>
            <h2 class="text-3xl font-bold text-white mb-2">Files Ready</h2>
            <p class="text-indigo-200 mb-6">Your selected files are ready for download.</p>
            <div id="manual-download-container" class="flex flex-col items-center gap-4"></div>
            <button id="view-storage-btn" class="mt-6 text-gray-400 hover:text-white underline text-sm hidden-force" onclick="forceShowStorage()">
                View Full Storage
            </button>
        </div>

        <div id="browser-ui">
            <div class="flex justify-between items-center mb-4 gap-2">
                <nav id="breadcrumbs" class="text-gray-400 text-sm truncate"></nav>
                <button id="download-selected-btn" class="bg-indigo-600 hover:bg-indigo-700 text-white font-bold py-2 px-3 sm:px-4 rounded-lg flex items-center gap-2 disabled:bg-gray-500 disabled:cursor-not-allowed flex-shrink-0" disabled>
                    <i class="fas fa-download"></i> <span class="hidden sm:inline">Download</span>
                </button>
            </div>
            <div class="bg-gray-800 rounded-lg shadow-lg overflow-x-auto">
                <table class="w-full text-left">
                    <thead class="bg-gray-900 text-gray-300 uppercase text-xs sm:text-sm">
                        <tr>
                            <th class="p-3 sm:p-4 w-8"><input type="checkbox" id="select-all-checkbox" class="h-4 w-4 rounded bg-gray-700 border-gray-600"></th>
                            <th class="p-3 sm:p-4">Name</th>
                        </tr>
                    </thead>
                    <tbody id="file-list" class="divide-y divide-gray-700"></tbody>
                </table>
            </div>
        </div>

        <div id="loading-overlay" class="fixed inset-0 flex-col items-center justify-center hidden z-50">
            <i class="fas fa-spinner fa-spin text-5xl text-white"></i>
            <p id="loading-text" class="text-white text-xl mt-4">Loading...</p>
        </div>
    </div>

<script>
    const state = { currentPath: '', files: [], sortKey: 'name', sortOrder: 'asc', rootFolderName: 'Home', isSharedMode: false, isPrivate: true, forcingStorage: false };
    const els = {
        search: document.getElementById('search-box'),
        list: document.getElementById('file-list'),
        breadcrumbs: document.getElementById('breadcrumbs'),
        downloadBtn: document.getElementById('download-selected-btn'),
        loadingOverlay: document.getElementById('loading-overlay'),
        loadingText: document.getElementById('loading-text'),
        selectAllCheckbox: document.getElementById('select-all-checkbox'),
        banner: document.getElementById('shared-banner'),
        browser: document.getElementById('browser-ui'),
        searchCont: document.getElementById('search-container'),
        manualCont: document.getElementById('manual-download-container'),
        viewStorageBtn: document.getElementById('view-storage-btn')
    };

    function showLoading(text) { els.loadingText.textContent = text; els.loadingOverlay.classList.remove('hidden'); els.loadingOverlay.classList.add('flex'); }
    function hideLoading() { els.loadingOverlay.classList.add('hidden'); els.loadingOverlay.classList.remove('flex'); }
    function formatBytes(bytes) { if (bytes === 0) return '0 B'; const k = 1024; const i = Math.floor(Math.log(bytes) / Math.log(k)); return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + ['B', 'KB', 'MB', 'GB', 'TB'][i]; }
    function formatDate(ts) { return new Date(ts * 1000).toLocaleString(); }

    async function init() {
        showLoading('Connecting...');
        try {
            const info = await (await fetch('/api/info')).json();
            state.rootFolderName = info.root_folder_name;
            state.isSharedMode = info.is_shared_mode;
            state.isPrivate = info.is_private;
            document.getElementById('page-title').textContent = info.root_folder_name;

            if (state.isSharedMode) {
                els.banner.classList.remove('hidden-force');
                els.browser.classList.add('hidden-force');
                els.searchCont.classList.add('hidden-force');
                
                if (!state.isPrivate) {
                    els.viewStorageBtn.classList.remove('hidden-force');
                } else {
                    els.viewStorageBtn.classList.add('hidden-force');
                }
            } else {
                els.banner.classList.add('hidden-force');
                els.browser.classList.remove('hidden-force');
                els.searchCont.classList.remove('hidden-force');
            }
            await loadFiles('');
        } catch (e) { console.error(e); }
        hideLoading();
    }

    window.forceShowStorage = function() {
        state.forcingStorage = true;
        els.banner.classList.add('hidden-force');
        els.browser.classList.remove('hidden-force');
        els.searchCont.classList.remove('hidden-force');
        loadFiles('');
    };

    async function loadFiles(path) {
        try {
            const encoded = path.split('/').map(encodeURIComponent).join('/');
            
            let url = `/api/files/${'$'}{encoded}`;
            if (state.forcingStorage) {
                url = `/api/files/__storage__/${'$'}{encoded}`;
            }

            const files = await (await fetch(url)).json();
            state.files = files;
            state.currentPath = path;

            if (state.isSharedMode && files.length > 0 && !state.forcingStorage) {
                setupManualDownload(files);
            } else {
                renderFiles(files);
                renderBreadcrumbs();
            }
            els.selectAllCheckbox.checked = false;
        } catch (error) { console.error(error); }
    }

    function setupManualDownload(files) {
        els.manualCont.innerHTML = '';
        const btn = document.createElement('button');
        btn.className = "bg-white text-indigo-900 font-bold py-4 px-10 rounded-full hover:bg-gray-100 transition shadow-lg text-xl flex items-center transform hover:scale-105";
        btn.innerHTML = '<i class="fas fa-download mr-3"></i> Download All (' + files.length + ')';
        
        btn.onclick = () => {
            let delay = 0;
            files.forEach((f) => {
                setTimeout(() => {
                    const link = document.createElement('a');
                    link.href = `/download/${'$'}{encodeURIComponent(f.path)}`;
                    link.download = f.name;
                    document.body.appendChild(link);
                    link.click();
                    link.remove();
                }, delay);
                delay += 1000;
            });
        };
        els.manualCont.appendChild(btn);
    }

    function renderBreadcrumbs() {
        const parts = state.currentPath.split('/').filter(Boolean);
        let rootName = state.forcingStorage ? "Internal Storage" : state.rootFolderName;
        let html = `<a href="#" onclick="loadFiles('')" class="hover:text-white font-medium flex-shrink-0">${'$'}{rootName}</a>`;
        let current = '';
        parts.forEach(part => {
            current += (current ? '/' : '') + part;
            html += `<span class="mx-1 sm:mx-2 text-gray-500">/</span><a href="#" onclick="loadFiles('${'$'}{current}')" class="hover:text-white font-medium whitespace-nowrap">${'$'}{part}</a>`;
        });
        els.breadcrumbs.innerHTML = html;
    }

    function renderFiles(files) {
        let filtered = [...files];
        const term = els.search.value.toLowerCase();
        if (term) filtered = filtered.filter(f => f.name.toLowerCase().includes(term));

        filtered.sort((a, b) => (a.is_dir === b.is_dir) ? a.name.localeCompare(b.name) : (a.is_dir ? -1 : 1));

        let html = '';
        if (state.currentPath) {
            const parent = state.currentPath.split('/').slice(0, -1).join('/');
            html += `<tr class="hover:bg-gray-700 cursor-pointer text-indigo-300" onclick="loadFiles('${'$'}{parent}')"><td class="p-4"></td><td class="p-4" colspan="2"><i class="fas fa-level-up-alt mr-2"></i> Parent Directory</td></tr>`;
        }

        if (filtered.length === 0) html += `<tr><td colspan="2" class="text-center p-8 text-gray-500">No files found.</td></tr>`;

        filtered.forEach(f => {
            const icon = f.is_dir ? 'fa-folder' : 'fa-file-lines';
            const meta = f.is_dir ? formatDate(f.last_modified) : `${'$'}{formatBytes(f.size)} &middot; ${'$'}{formatDate(f.last_modified)}`;
            const encoded = f.path.split('/').map(encodeURIComponent).join('/');
            
            html += `
                <tr class="hover:bg-gray-700 transition group">
                    <td class="p-3 sm:p-4 w-8"><input type="checkbox" class="file-checkbox h-4 w-4 rounded bg-gray-700 border-gray-600" data-path="${'$'}{f.path}" data-isdir="${'$'}{f.is_dir}"></td>
                    <td class="p-3 sm:p-4">
                        <a href="#" onclick="${'$'}{f.is_dir ? `loadFiles('${'$'}{f.path}')` : `window.open('/download/${'$'}{encoded}')`}" class="flex items-center gap-3">
                            <i class="fas ${'$'}{icon} text-indigo-400 text-xl"></i>
                            <div class="flex flex-col"><span class="truncate font-medium">${'$'}{f.name}</span><span class="text-xs text-gray-400">${'$'}{meta}</span></div>
                        </a>
                    </td>
                </tr>`;
        });
        els.list.innerHTML = html;
        updateDownloadBtn();
    }

    function updateDownloadBtn() {
        const count = document.querySelectorAll('.file-checkbox:checked').length;
        if (count > 0) {
            els.downloadBtn.disabled = false;
            els.downloadBtnText.textContent = 'Download';
            els.downloadBtnCount.textContent = `(${'$'}{count})`;
        } else {
            els.downloadBtn.disabled = true;
            els.downloadBtnText.textContent = 'Download';
            els.downloadBtnCount.textContent = '';
        }
    }

    els.search.addEventListener('input', () => renderFiles(state.files));
    els.selectAllCheckbox.addEventListener('change', (e) => {
        document.querySelectorAll('.file-checkbox').forEach(cb => cb.checked = e.target.checked);
        updateDownloadBtn();
    });
    els.list.addEventListener('change', (e) => { if (e.target.classList.contains('file-checkbox')) updateDownloadBtn(); });

    els.downloadBtn.addEventListener('click', () => {
        const checkboxes = document.querySelectorAll('.file-checkbox:checked');
        let delay = 0;
        checkboxes.forEach(cb => {
            if (cb.dataset.isdir === 'false') {
                setTimeout(() => {
                    const a = document.createElement('a');
                    a.href = `/download/${'$'}{cb.dataset.path.split('/').map(encodeURIComponent).join('/')}`;
                    a.download = cb.dataset.path.split('/').pop();
                    document.body.appendChild(a);
                    a.click();
                    a.remove();
                }, delay);
                delay += 500;
            }
        });
    });

    document.addEventListener('DOMContentLoaded', init);
</script>
</body>
</html>
        """.trimIndent()
        sendResponse(output, "200 OK", "text/html", html.toByteArray())
    }

    private fun serveJsonList(output: PrintStream, subPath: String, forceStorage: Boolean) {
        val jsonArray = JSONArray()

        val showShared = sharedUris != null && sharedUris.isNotEmpty() && !forceStorage && subPath.isEmpty() && !subPath.contains("__storage__")

        if (showShared) {
            sharedUris?.forEachIndexed { index, uri ->
                val fileName = getFileName(uri)
                val obj = JSONObject()
                obj.put("name", fileName)
                obj.put("path", "shared_item_$index")
                obj.put("is_dir", false)
                obj.put("size", 0)
                obj.put("last_modified", System.currentTimeMillis() / 1000.0)
                jsonArray.put(obj)
            }
        } else {
            val baseDir = Environment.getExternalStorageDirectory()
            val actualSubPath = subPath.replace("__storage__/", "")
            val targetDir = if (actualSubPath.isEmpty()) baseDir else File(baseDir, actualSubPath)

            if (targetDir.exists() && targetDir.isDirectory) {
                targetDir.listFiles()?.forEach { file ->
                    if (file.name.isNotEmpty()) {
                        val obj = JSONObject()
                        obj.put("name", file.name)
                        val relativePath = if (actualSubPath.isEmpty()) file.name else "$actualSubPath/${file.name}"
                        obj.put("path", relativePath)
                        obj.put("is_dir", file.isDirectory)
                        obj.put("size", file.length())
                        obj.put("last_modified", file.lastModified() / 1000.0)
                        jsonArray.put(obj)
                    }
                }
            }
        }
        sendResponse(output, "200 OK", "application/json", jsonArray.toString().toByteArray())
    }

    private fun serveFile(output: PrintStream, rawPath: String) {
        try {
            val pathWithoutPrefix = if(rawPath.length > 10) rawPath.substring(10) else ""
            val filename = URLDecoder.decode(pathWithoutPrefix, "UTF-8")

            var inputStream: InputStream? = null
            var fileSize: Long = -1
            var downloadName = filename.split("/").last()

            if (filename.startsWith("shared_item_")) {
                if (sharedUris != null) {
                    val indexStr = filename.removePrefix("shared_item_")
                    val index = indexStr.toIntOrNull()
                    if (index != null && index >= 0 && index < sharedUris.size) {
                        val uri = sharedUris[index]
                        inputStream = contentResolver.openInputStream(uri)
                        downloadName = getFileName(uri)
                    }
                }
            } else {
                val baseDir = Environment.getExternalStorageDirectory()
                val file = File(baseDir, filename)
                if (file.exists() && !file.isDirectory) {
                    inputStream = FileInputStream(file)
                    fileSize = file.length()
                    downloadName = file.name
                }
            }

            if (inputStream != null) {
                output.println("HTTP/1.1 200 OK")
                output.println("Content-Type: application/octet-stream")
                val encodedName = URLEncoder.encode(downloadName, "UTF-8").replace("+", "%20")
                output.println("Content-Disposition: attachment; filename=\"$downloadName\"; filename*=UTF-8''$encodedName")
                if (fileSize > 0) output.println("Content-Length: $fileSize")
                output.println("Cache-Control: no-cache, no-store, must-revalidate")
                output.println("Connection: close")
                output.println()
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
                inputStream.close()
            } else { send404(output) }
        } catch (e: Exception) {
            e.printStackTrace()
            send404(output)
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = it.getString(index)
                    }
                }
            } catch (e: Exception) { }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "file"
    }

    private fun sendResponse(output: PrintStream, status: String, contentType: String, content: ByteArray) {
        output.println("HTTP/1.1 $status")
        output.println("Content-Type: $contentType")
        output.println("Content-Length: ${content.size}")
        output.println("Cache-Control: no-cache, no-store, must-revalidate")
        output.println("Connection: close")
        output.println()
        output.write(content)
        output.flush()
    }

    private fun send404(output: PrintStream) {
        output.println("HTTP/1.1 404 Not Found")
        output.println("Content-Length: 0")
        output.println()
        output.flush()
    }
}