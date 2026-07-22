package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.SharedFolder
import com.example.server.LocalHttpServer
import com.example.ui.FileSystemPickerModal
import com.example.ui.PictureSnapshotGallery
import com.example.ui.QrCodeModal
import com.example.ui.QrScannerModal
import com.example.ui.theme.MyApplicationTheme
import java.net.URLDecoder
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

enum class AppTab {
    HOME, DEVICES, SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val viewModel: SharedFolderViewModel = viewModel(
        factory = SharedFolderViewModel.Factory(context.applicationContext as Application)
    )

    val folders by viewModel.allFolders.collectAsStateWithLifecycle()
    var currentTab by remember { mutableStateOf(AppTab.HOME) }

    // Dialog state for adding a folder via FileSystemPickerModal
    var showPickerModal by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("8080") }

    // Folder deletion confirmation
    var folderToDelete by remember { mutableStateOf<SharedFolder?>(null) }

    // Selected folder to share after notification permission is checked
    var activeFolderToStart by remember { mutableStateOf<SharedFolder?>(null) }

    // Dynamic QR Modal and QR Scanner Modal state
    var showQrModalForFolder by remember { mutableStateOf<SharedFolder?>(null) }
    var showQrScannerModal by remember { mutableStateOf(false) }

    // Permission launcher for notifications (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            activeFolderToStart?.let { viewModel.startShare(it) }
        } else {
            Toast.makeText(context, "Notification permission is required to display the status indicator", Toast.LENGTH_LONG).show()
        }
    }

    // Storage Access Framework picker launcher
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.e("SAF", "Failed persistable uri permission", e)
            }
            selectedUri = uri.toString()
            
            // Extract display name of selected folder
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            customName = documentFile?.name ?: "Shared Folder"
            showPickerModal = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "LS",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Column {
                            Text(
                                text = "LocalShare",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Local file network sharing",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showQrScannerModal = true },
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Scan QR Code",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Quick profile circle
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "JD",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                NavigationBarItem(
                    selected = currentTab == AppTab.HOME,
                    onClick = { currentTab = AppTab.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home", fontWeight = if (currentTab == AppTab.HOME) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.DEVICES,
                    onClick = { currentTab = AppTab.DEVICES },
                    icon = { Icon(Icons.Default.List, contentDescription = "Devices") },
                    label = { Text("Devices", fontWeight = if (currentTab == AppTab.DEVICES) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.SETTINGS,
                    onClick = { currentTab = AppTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings", fontWeight = if (currentTab == AppTab.SETTINGS) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppTab.HOME -> HomeScreen(
                    folders = folders,
                    onStartShare = { folder ->
                        activeFolderToStart = folder
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val permissionCheck = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.startShare(folder)
                            }
                        } else {
                            viewModel.startShare(folder)
                        }
                    },
                    onStopShare = { viewModel.stopShare() },
                    onDeleteFolder = { folderToDelete = it },
                    onNewShareClick = { showPickerModal = true },
                    onOpenQrCode = { folder -> showQrModalForFolder = folder },
                    onOpenQrScanner = { showQrScannerModal = true }
                )
                AppTab.DEVICES -> DevicesScreen(onOpenQrScanner = { showQrScannerModal = true })
                AppTab.SETTINGS -> SettingsScreen()
            }

            // File System Picker Modal Component
            if (showPickerModal) {
                FileSystemPickerModal(
                    onDismissRequest = { showPickerModal = false },
                    onFolderSelected = { name, uriOrPath, port ->
                        viewModel.addFolder(name, uriOrPath, port)
                        showPickerModal = false
                    },
                    onLaunchSystemPicker = {
                        folderLauncher.launch(null)
                    },
                    initialUriOrPath = selectedUri,
                    initialName = customName
                )
            }

            // Folder Delete Dialog
            folderToDelete?.let { folder ->
                AlertDialog(
                    onDismissRequest = { folderToDelete = null },
                    title = { Text("Delete Share Confirmation", fontWeight = FontWeight.Bold) },
                    text = { Text("Are you sure you want to stop sharing and delete \"${folder.name}\"?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteFolder(folder)
                                folderToDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { folderToDelete = null }) {
                            Text("Cancel")
                        }
                    },
                    shape = RoundedCornerShape(28.dp)
                )
            }

            // Dynamic QR Code Share Modal Component
            showQrModalForFolder?.let { folder ->
                val ipAddress = ShareService.getLocalIpAddress() ?: "127.0.0.1"
                QrCodeModal(
                    folderName = folder.name,
                    ipAddress = ipAddress,
                    port = folder.port,
                    onDismissRequest = { showQrModalForFolder = null },
                    onOpenScanner = {
                        showQrModalForFolder = null
                        showQrScannerModal = true
                    }
                )
            }

            // QR Code Peer Scanner Modal Component
            if (showQrScannerModal) {
                QrScannerModal(
                    onDismissRequest = { showQrScannerModal = false },
                    onPeerConnected = { peerUrl, peerName ->
                        Toast.makeText(context, "Connected to $peerName at $peerUrl", Toast.LENGTH_SHORT).show()
                        LocalHttpServer.activePeers[peerUrl] = System.currentTimeMillis()
                        showQrScannerModal = false
                    }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    folders: List<SharedFolder>,
    onStartShare: (SharedFolder) -> Unit,
    onStopShare: (SharedFolder) -> Unit,
    onDeleteFolder: (SharedFolder) -> Unit,
    onNewShareClick: () -> Unit,
    onOpenQrCode: (SharedFolder) -> Unit,
    onOpenQrScanner: () -> Unit
) {
    val activeFolder = folders.find { it.isActive }
    val ipAddress = ShareService.getLocalIpAddress() ?: "0.0.0.0"
    val displaySnapshotFolder = activeFolder ?: folders.firstOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
        ) {
            // Host Active Server Status Card
            item {
                ActiveServerCard(
                    activeFolder = activeFolder,
                    ipAddress = ipAddress,
                    onOpenQrCode = { activeFolder?.let(onOpenQrCode) },
                    onOpenQrScanner = onOpenQrScanner
                )
            }

            // Shared Picture Snapshots Section
            if (displaySnapshotFolder != null) {
                item {
                    PictureSnapshotGallery(
                        uriString = displaySnapshotFolder.uriString,
                        folderName = displaySnapshotFolder.name
                    )
                }
            }

            // Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Shared Folders (${folders.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            if (folders.isEmpty()) {
                item {
                    EmptyStateView(onNewShareClick)
                }
            } else {
                items(folders, key = { it.id }) { folder ->
                    FolderItemRow(
                        folder = folder,
                        onToggleActive = {
                            if (folder.isActive) {
                                onStopShare(folder)
                            } else {
                                onStartShare(folder)
                            }
                        },
                        onDelete = { onDeleteFolder(folder) },
                        onOpenQrCode = { onOpenQrCode(folder) }
                    )
                }
            }
        }

        // Add Folder FAB styled elegantly according to Design guidelines
        ExtendedFloatingActionButton(
            text = { Text("New Share", fontWeight = FontWeight.Bold) },
            icon = { Icon(Icons.Default.Add, contentDescription = "New Share") },
            onClick = onNewShareClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("submit_button"),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun ActiveServerCard(
    activeFolder: SharedFolder?,
    ipAddress: String,
    onOpenQrCode: () -> Unit,
    onOpenQrScanner: () -> Unit
) {
    val isLive = activeFolder != null
    val containerColor = if (isLive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isLive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "Active Host",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = Build.MODEL,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .background(
                            color = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isLive) "LIVE" else "OFFLINE",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
                Text(
                    text = if (isLive) "$ipAddress:${activeFolder?.port}" else "0.0.0.0",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor.copy(alpha = 0.9f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLive) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.05f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "ACTIVE SHARE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = activeFolder?.name ?: "-",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLive) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.05f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "TRANSFERRED",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val transferredBytes = if (isLive) {
                            activeFolder!!.bytesTransferred + ShareService.bytesTransferredInMemory
                        } else {
                            0L
                        }
                        Text(
                            text = humanReadableByteCount(transferredBytes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Direct File Transfer QR Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenQrCode,
                    enabled = isLive,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("show_qr_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "QR Code",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Show QR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onOpenQrScanner,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_qr_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Scan QR",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Peer", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun FolderItemRow(
    folder: SharedFolder,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onOpenQrCode: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = folder.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = getDisplayPath(folder.uriString),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Port: ${folder.port}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // QR button
                IconButton(
                    onClick = onOpenQrCode,
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "QR Code",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Play / Stop share toggle button
                IconButton(
                    onClick = onToggleActive,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (folder.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (folder.isActive) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = if (folder.isActive) "Stop Sharing" else "Start Sharing",
                        tint = if (folder.isActive) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Delete button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Share",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(onNewShareClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "No folders shared yet",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = "Tap 'New Share' to pick a folder from your storage and start sharing instantly.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        Button(
            onClick = onNewShareClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Select Folder")
        }
    }
}

@Composable
fun DevicesScreen(
    onOpenQrScanner: () -> Unit = {}
) {
    val activePeersMap = LocalHttpServer.activePeers
    val activePeersList = remember(activePeersMap) {
        activePeersMap.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connected Devices",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Devices connected for direct local sharing",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                Button(
                    onClick = onOpenQrScanner,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Scan QR",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan QR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (activePeersList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No active connections yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Share a folder and visit the server link from another device on your network to see it appear here.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(activePeersList) { (ip, lastSeen) ->
                val isOnline = System.currentTimeMillis() - lastSeen < 60_000 // online if seen in last 1 min
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device Peer ($ip)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Last seen: ${Date(lastSeen)}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val ip = ShareService.getLocalIpAddress() ?: "0.0.0.0"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    text = "Connection Guide",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "How to access files from other devices on your local network",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Step-by-Step Guide",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GuideStepItem(
                            stepNumber = "1",
                            title = "Connect to same Wi-Fi",
                            description = "Ensure this Android device and the target device (computer, laptop, tablet, phone) are connected to the exact same local Wi-Fi router."
                        )
                        GuideStepItem(
                            stepNumber = "2",
                            title = "Choose folder & Start",
                            description = "Add a folder in the 'Home' tab and click the 'Play' icon. This fires up the secure local server."
                        )
                        GuideStepItem(
                            stepNumber = "3",
                            title = "Enter URL in browser",
                            description = "Open Google Chrome, Safari, Edge, or Firefox on your computer and navigate to the local IP address (e.g. http://$ip:8080)."
                        )
                        GuideStepItem(
                            stepNumber = "4",
                            title = "Enjoy full access",
                            description = "You can instantly browse folders, download files, view media streams, create subfolders, and upload files from your computer back to this phone!"
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Why LocalShare?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "LocalShare runs a highly optimized lightweight server directly in memory on your Android device. It uses SAF (Storage Access Framework) to abide strictly by Scoped Storage safety standards, meaning it respects sandbox file security.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun GuideStepItem(
    stepNumber: String,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                lineHeight = 15.sp
            )
        }
    }
}

// Utility to get beautiful path names
fun getDisplayPath(uriString: String): String {
    if (uriString.isEmpty()) return ""
    return try {
        val uri = Uri.parse(uriString)
        val path = uri.path ?: return uriString
        if (path.contains("document/")) {
            val docPart = path.substringAfter("document/", "")
            if (docPart.isNotEmpty()) {
                URLDecoder.decode(docPart, "UTF-8").replace("primary:", "Internal:")
            } else {
                uriString
            }
        } else if (path.contains("tree/")) {
            val treePart = path.substringAfter("tree/", "")
            if (treePart.isNotEmpty()) {
                URLDecoder.decode(treePart, "UTF-8").replace("primary:", "Internal:")
            } else {
                uriString
            }
        } else {
            URLDecoder.decode(path, "UTF-8")
        }
    } catch (e: Exception) {
        uriString
    }
}

fun humanReadableByteCount(bytes: Long): String {
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
    return String.format(Locale.US, "%.1f %ciB", value / 1024.0, ci.current())
}
