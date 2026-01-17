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

class WebServer(
    private val port: Int,
    private val contentResolver: ContentResolver,
    private val sharedUris: List<Uri>?,
    private val rootPath: File?
) : Thread() {

    private var serverSocket: ServerSocket? = null
    var isRunning = false

    override fun run() {
        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            Log.d("WebServer", "Server started on port $port")

            while (isRunning) {
                val socket = serverSocket?.accept() ?: break
                handleClient(socket)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintStream(socket.getOutputStream())

                val requestLine = input.readLine()
                if (requestLine == null) {
                    socket.close()
                    return@Thread
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    socket.close()
                    return@Thread
                }

                val method = parts[0]
                val path = URLDecoder.decode(parts[1], "UTF-8")

                if (method == "GET") {
                    when {
                        path == "/" || path == "/files" -> serveHtml(output)
                        path == "/api/info" -> serveInfo(output)
                        path.startsWith("/api/files") -> {
                            val subPath = if (path.length > 10) path.substring(11) else ""
                            serveJsonList(output, subPath)
                        }
                        path.startsWith("/download/") -> serveFile(output, path)
                        else -> send404(output)
                    }
                }

                output.close()
                input.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun serveInfo(output: PrintStream) {
        val isSharedMode = sharedUris != null && sharedUris.isNotEmpty()
        val rootName = if (isSharedMode) "Shared Files" else "Internal Storage"

        val json = JSONObject()
        json.put("root_folder_name", rootName)
        json.put("is_shared_mode", isSharedMode)
        sendResponse(output, "200 OK", "application/json", json.toString().toByteArray())
    }

    private fun serveHtml(output: PrintStream) {
        val html = """
<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>QuickDrop - File Browser</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css">
    <style>
        body { font-family: 'Inter', sans-serif; background-color: #111827; color: #e5e7eb; -webkit-tap-highlight-color: transparent; }
        #download-selected-btn, #sort-control { transition: all 0.2s; }
        #loading-overlay { background-color: rgba(0,0,0,0.7); backdrop-filter: blur(5px); }
        #breadcrumbs { display: flex; flex-wrap: nowrap; overflow: hidden; }
        .sort-control-btn { user-select: none; }
        
        #shared-banner { display: none; }
        .shared-mode #shared-banner { display: block; }
        .shared-mode #browser-ui { display: none; }
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

        <div id="shared-banner" class="mb-6 bg-indigo-900/40 border border-indigo-500 rounded-lg p-8 text-center shadow-xl">
            <div class="mb-4">
                <i class="fas fa-file-arrow-down text-5xl text-indigo-400 animate-bounce"></i>
            </div>
            <h2 class="text-3xl font-bold text-white mb-2">Files Ready</h2>
            <p class="text-indigo-200 mb-8 text-lg">Your download should start automatically.</p>
            <div id="manual-download-container" class="flex flex-col items-center gap-4"></div>
        </div>

        <div id="browser-ui">
            <div class="flex justify-between items-center mb-4 gap-2">
                <nav id="breadcrumbs" class="text-gray-400 text-sm truncate"></nav>
                <button id="download-selected-btn" class="bg-indigo-600 hover:bg-indigo-700 text-white font-bold py-2 px-3 sm:px-4 rounded-lg flex items-center gap-2 disabled:bg-gray-500 disabled:cursor-not-allowed flex-shrink-0" disabled>
                    <i class="fas fa-download"></i>
                    <span id="download-btn-text" class="hidden sm:inline">Download</span>
                    <span id="download-btn-count" class="hidden sm:inline"></span>
                </button>
            </div>

            <div class="bg-gray-800 rounded-lg shadow-lg overflow-x-auto">
                <table class="w-full text-left">
                    <thead class="bg-gray-900 text-gray-300 uppercase text-xs sm:text-sm">
                        <tr>
                            <th class="p-3 sm:p-4 w-8"><input type="checkbox" id="select-all-checkbox" class="h-4 w-4 rounded bg-gray-700 border-gray-600"></th>
                            <th class="p-3 sm:p-4">
                                <div class="relative flex items-center gap-2">
                                    <div id="sort-control" class="flex items-center gap-2 cursor-pointer sort-control-btn hover:text-white">
                                        <span id="sort-key-display">Name</span>
                                        <i class="fas fa-caret-down"></i>
                                    </div>
                                    <button id="sort-order-btn" class="sort-control-btn hover:text-white p-1 rounded-md"><i id="sort-order-icon" class="fas fa-arrow-down"></i></button>
                                    
                                    <div id="sort-dropdown" class="absolute top-full left-0 mt-2 w-32 bg-gray-700 border border-gray-600 rounded-md shadow-lg hidden z-10">
                                        <a href="#" class="block px-4 py-2 text-sm hover:bg-gray-600" data-sort="name">Name</a>
                                        <a href="#" class="block px-4 py-2 text-sm hover:bg-gray-600" data-sort="last_modified">Date</a>
                                        <a href="#" class="block px-4 py-2 text-sm hover:bg-gray-600" data-sort="size">Size</a>
                                    </div>
                                </div>
                            </th>
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
    const state = {
        currentPath: '',
        files: [],
        sortKey: 'name',
        sortOrder: 'asc',
        rootFolderName: 'Home',
        isSharedMode: false
    };

    const searchBox = document.getElementById('search-box');
    const searchContainer = document.getElementById('search-container');
    const fileListBody = document.getElementById('file-list');
    const breadcrumbs = document.getElementById('breadcrumbs');
    const selectAllCheckbox = document.getElementById('select-all-checkbox');
    const downloadBtn = document.getElementById('download-selected-btn');
    const downloadBtnText = document.getElementById('download-btn-text');
    const downloadBtnCount = document.getElementById('download-btn-count');
    const loadingOverlay = document.getElementById('loading-overlay');
    const loadingText = document.getElementById('loading-text');
    const manualDownloadContainer = document.getElementById('manual-download-container');
    
    const sortControl = document.getElementById('sort-control');
    const sortKeyDisplay = document.getElementById('sort-key-display');
    const sortOrderBtn = document.getElementById('sort-order-btn');
    const sortOrderIcon = document.getElementById('sort-order-icon');
    const sortDropdown = document.getElementById('sort-dropdown');

    function showLoading(text) {
        loadingText.textContent = text;
        loadingOverlay.classList.remove('hidden');
        loadingOverlay.classList.add('flex');
    }
    
    function hideLoading() {
        loadingOverlay.classList.add('hidden');
        loadingOverlay.classList.remove('flex');
    }

    function formatBytes(bytes, decimals = 2) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const dm = decimals < 0 ? 0 : decimals;
        const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
    }

    function formatDate(timestamp) {
        return new Date(timestamp * 1000).toLocaleString(undefined, {
            year: 'numeric', month: 'short', day: 'numeric',
            hour: 'numeric', minute: '2-digit'
        });
    }

    function renderBreadcrumbs() {
        const parts = state.currentPath.split('/').filter(Boolean);
        let pathSegments = [];
        let currentPath = '';

        const homeLink = `<a href="#" data-path="" class="hover:text-white font-medium flex-shrink-0">${'$'}{state.rootFolderName}</a>`;
        pathSegments.push(homeLink);

        for (const part of parts) {
            currentPath += (currentPath ? '/' : '') + part;
            const separator = `<span class="mx-1 sm:mx-2 text-gray-500 flex-shrink-0">/</span>`;
            const link = `<a href="#" data-path="${'$'}{currentPath}" class="hover:text-white font-medium whitespace-nowrap">${'$'}{part}</a>`;
            pathSegments.push(separator);
            pathSegments.push(link);
        }
        breadcrumbs.innerHTML = pathSegments.join('');

        setTimeout(() => {
            if (breadcrumbs.scrollWidth > breadcrumbs.clientWidth && pathSegments.length > 3) {
                const ellipsis = `<span class="mx-1 sm:mx-2 text-gray-500 flex-shrink-0">...</span>`;
                while (breadcrumbs.scrollWidth > breadcrumbs.clientWidth && pathSegments.length > 5) {
                    pathSegments.splice(1, 2); 
                }
                if (pathSegments.length > 3) {
                    pathSegments.splice(1, 0, ellipsis);
                }
                breadcrumbs.innerHTML = pathSegments.join('');
            }
        }, 0);
    }

    function renderFileList() {
        let filteredFiles = [...state.files];
        const searchTerm = searchBox.value.toLowerCase();
        if (searchTerm) {
            filteredFiles = filteredFiles.filter(f => f.name.toLowerCase().includes(searchTerm));
        }

        const folders = filteredFiles.filter(f => f.is_dir);
        const files = filteredFiles.filter(f => !f.is_dir);

        const sorter = (a, b) => {
            const valA = a[state.sortKey];
            const valB = b[state.sortKey];
            let comparison = 0;
            if (typeof valA === 'string') {
                comparison = valA.localeCompare(valB, undefined, { numeric: true, sensitivity: 'base' });
            } else {
                comparison = valA - valB;
            }
            if (comparison === 0) {
                comparison = a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
            }
            return state.sortOrder === 'asc' ? comparison : -comparison;
        };

        folders.sort(sorter);
        files.sort(sorter);

        const sortedList = [...folders, ...files];

        let html = '';
        if (state.currentPath && !state.isSharedMode) {
            const parentPath = state.currentPath.substring(0, state.currentPath.lastIndexOf('/'));
            html += `
                <tr class="hover:bg-gray-700 cursor-pointer text-indigo-300" onclick="navigateTo('${'$'}{parentPath}')">
                    <td class="p-3 sm:p-4"></td>
                    <td class="p-3 sm:p-4">
                        <div class="flex items-center gap-3">
                            <i class="fas fa-level-up-alt"></i>
                            <span>Parent Directory</span>
                        </div>
                    </td>
                </tr>
            `;
        }

        if (sortedList.length === 0) {
            html += `<tr><td colspan="2" class="text-center p-8 text-gray-500">No files found.</td></tr>`;
        }

        sortedList.forEach(file => {
            const icon = file.is_dir ? 'fa-folder' : 'fa-file-lines';
            const metadata = file.is_dir 
                ? formatDate(file.last_modified) 
                : `${'$'}{formatBytes(file.size)} &middot; ${'$'}{formatDate(file.last_modified)}`;

            html += `
                <tr class="hover:bg-gray-700">
                    <td class="p-3 sm:p-4 w-8"><input type="checkbox" class="file-checkbox h-4 w-4 rounded bg-gray-700 border-gray-600" data-path="${'$'}{file.path}" data-is-dir="${'$'}{file.is_dir}"></td>
                    <td class="p-3 sm:p-4">
                        <a href="#" data-path="${'$'}{file.path}" data-is-dir="${'$'}{file.is_dir}" class="flex items-center gap-3">
                            <i class="fas ${'$'}{icon} text-indigo-400 text-xl"></i>
                            <div class="flex flex-col">
                                <span class="truncate font-medium">${'$'}{file.name}</span>
                                <span class="text-xs text-gray-400">${'$'}{metadata}</span>
                            </div>
                        </a>
                    </td>
                </tr>
            `;
        });
        fileListBody.innerHTML = html;
        updateDownloadButton();
    }

    async function navigateTo(path) {
        showLoading('Loading files...');
        try {
            const encodedPath = path.split('/').map(encodeURIComponent).join('/');
            const response = await fetch(`/api/files/${'$'}{encodedPath}`);
            if (!response.ok) throw new Error('Failed to load files');
            const files = await response.json();
            
            state.files = files;
            state.currentPath = path;

            // --- AUTO DOWNLOAD LOGIC (Shared Mode) ---
            if (state.isSharedMode && files.length > 0) {
                manualDownloadContainer.innerHTML = '';
                
                const btn = document.createElement('button');
                btn.className = "bg-white text-indigo-900 font-bold py-4 px-10 rounded-full hover:bg-gray-100 transition shadow-lg text-xl flex items-center transform hover:scale-105";
                btn.innerHTML = '<i class="fas fa-download mr-3"></i> Download All Files';
                btn.onclick = () => {
                    files.forEach((file, index) => {
                        setTimeout(() => {
                            const iframe = document.createElement('iframe');
                            iframe.style.display = 'none';
                            iframe.src = `/download/${'$'}{encodeURIComponent(file.path)}`;
                            document.body.appendChild(iframe);
                            setTimeout(() => iframe.remove(), 60000);
                        }, index * 1000);
                    });
                };
                manualDownloadContainer.appendChild(btn);

                // Auto-Trigger Download loop
                files.forEach((file, index) => {
                    setTimeout(() => {
                        const iframe = document.createElement('iframe');
                        iframe.style.display = 'none';
                        iframe.src = `/download/${'$'}{encodeURIComponent(file.path)}`;
                        document.body.appendChild(iframe);
                        setTimeout(() => iframe.remove(), 60000); 
                    }, 1000 + (index * 1000));
                });
            } else {
                renderBreadcrumbs();
                renderFileList();
            }
            
            selectAllCheckbox.checked = false;
        } catch (error) {
            console.error(error);
        } finally {
            hideLoading();
        }
    }
    
    function updateDownloadButton() {
        const count = document.querySelectorAll('.file-checkbox:checked').length;
        if (count > 0) {
            downloadBtn.disabled = false;
            downloadBtnText.textContent = 'Download';
            downloadBtnCount.textContent = `(${'$'}{count})`;
        } else {
            downloadBtn.disabled = true;
            downloadBtnText.textContent = 'Download';
            downloadBtnCount.textContent = '';
        }
    }

    function updateSortUI() {
        const keyMap = { name: 'Name', last_modified: 'Date', size: 'Size' };
        sortKeyDisplay.textContent = keyMap[state.sortKey];
        sortOrderIcon.className = `fas fa-arrow-${'$'}{state.sortOrder === 'asc' ? 'down' : 'up'}`;
    }

    // --- Event Listeners ---
    searchBox.addEventListener('input', renderFileList);
    
    sortControl.addEventListener('click', (e) => {
        e.stopPropagation();
        sortDropdown.classList.toggle('hidden');
    });

    sortOrderBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        state.sortOrder = state.sortOrder === 'asc' ? 'desc' : 'asc';
        sortDropdown.classList.add('hidden');
        updateSortUI();
        renderFileList();
    });

    sortDropdown.addEventListener('click', (e) => {
        e.preventDefault();
        const target = e.target.closest('a');
        if (target && target.dataset.sort) {
            state.sortKey = target.dataset.sort;
            sortDropdown.classList.add('hidden');
            updateSortUI();
            renderFileList();
        }
    });
    
    document.addEventListener('click', () => {
        sortDropdown.classList.add('hidden');
    });

    breadcrumbs.addEventListener('click', e => {
        if (e.target.tagName === 'A') {
            e.preventDefault();
            navigateTo(e.target.dataset.path);
        }
    });

    fileListBody.addEventListener('click', e => {
        const link = e.target.closest('a');
        if (link) {
            e.preventDefault();
            if (link.dataset.isDir === 'true') {
                navigateTo(link.dataset.path);
            } else {
                const encodedPath = link.dataset.path.split('/').map(encodeURIComponent).join('/');
                window.open(`/download/${'$'}{encodedPath}`, '_blank');
            }
        }
    });
    
    selectAllCheckbox.addEventListener('change', () => {
        document.querySelectorAll('.file-checkbox').forEach(cb => {
            cb.checked = selectAllCheckbox.checked;
        });
        updateDownloadButton();
    });

    fileListBody.addEventListener('change', e => {
        if (e.target.classList.contains('file-checkbox')) updateDownloadButton();
    });
    
    downloadBtn.addEventListener('click', () => {
        const checkboxes = document.querySelectorAll('.file-checkbox:checked');
        let i = 0;
        function downloadNext() {
            if (i >= checkboxes.length) return;
            const checkbox = checkboxes[i];
            i++;
            if (checkbox.dataset.isDir === 'true') {
                downloadNext(); 
                return;
            }
            const path = checkbox.dataset.path;
            const encodedPath = path.split('/').map(encodeURIComponent).join('/');
            const a = document.createElement('a');
            a.href = `/download/${'$'}{encodedPath}`;
            a.download = path.split('/').pop();
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(downloadNext, 500);
        }
        downloadNext();
    });

    async function initializeApp() {
        showLoading('Connecting...');
        try {
            const response = await fetch('/api/info');
            const data = await response.json();
            state.rootFolderName = data.root_folder_name;
            state.isSharedMode = data.is_shared_mode;
            document.getElementById('page-title').textContent = data.root_folder_name;

            if (state.isSharedMode) {
                document.body.classList.add('shared-mode');
                document.getElementById('search-container').classList.add('hidden');
            } else {
                document.body.classList.remove('shared-mode');
                document.getElementById('search-container').classList.remove('hidden');
            }

        } catch (e) {
            console.error("Could not fetch info.", e);
        }
        await navigateTo('');
        hideLoading();
    }

    document.addEventListener('DOMContentLoaded', initializeApp);
</script>
</body>
</html>
        """.trimIndent()
        sendResponse(output, "200 OK", "text/html", html.toByteArray())
    }

    private fun serveJsonList(output: PrintStream, subPath: String) {
        val jsonArray = JSONArray()

        if (sharedUris != null && sharedUris.isNotEmpty()) {
            if (subPath.isEmpty()) {
                sharedUris.forEachIndexed { index, uri ->
                    val fileName = getFileName(uri)
                    val obj = JSONObject()
                    obj.put("name", fileName)
                    obj.put("path", "shared_item_$index")
                    obj.put("is_dir", false)
                    obj.put("size", 0)
                    obj.put("last_modified", System.currentTimeMillis() / 1000.0)
                    jsonArray.put(obj)
                }
            }
        }
        else {
            // BROWSE MODE: Force Internal Storage Root
            val baseDir = Environment.getExternalStorageDirectory()
            val targetDir = if (subPath.isEmpty()) baseDir else File(baseDir, subPath)

            if (targetDir.exists() && targetDir.isDirectory) {
                val files = targetDir.listFiles()
                files?.forEach { file ->
                    // Show all files unless explicitly starting with . AND being a system/hidden concept
                    // This change ensures PDFs and other regular files are shown even if they have odd naming
                    if (file.name.isNotEmpty()) {
                        val obj = JSONObject()
                        obj.put("name", file.name)
                        val relativePath = if (subPath.isEmpty()) file.name else "$subPath/${file.name}"
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

    private fun serveFile(output: PrintStream, path: String) {
        val rawPath = if(path.length > 10) path.substring(10) else ""
        val filename = rawPath.split("/").joinToString("/") { URLDecoder.decode(it, "UTF-8") }

        try {
            var inputStream: InputStream? = null
            var fileSize: Long = -1
            var downloadName = filename.split("/").last()

            if (sharedUris != null && filename.startsWith("shared_item_")) {
                val index = filename.removePrefix("shared_item_").toIntOrNull()
                if (index != null && index >= 0 && index < sharedUris.size) {
                    val uri = sharedUris[index]
                    inputStream = contentResolver.openInputStream(uri)
                    downloadName = getFileName(uri)
                }
            } else {
                // Serve from Internal Storage Root
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
                output.println("Content-Disposition: attachment; filename=\"$downloadName\"")
                if (fileSize > 0) output.println("Content-Length: $fileSize")
                output.println("Cache-Control: no-cache, no-store, must-revalidate")
                output.println("Connection: close")
                output.println()

                val buffer = ByteArray(65536) // 64KB buffer
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
                inputStream.close()
            } else {
                send404(output)
            }
        } catch (e: Exception) {
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
            } catch (e: Exception) {
                Log.e("WebServer", "Error getting filename", e)
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
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