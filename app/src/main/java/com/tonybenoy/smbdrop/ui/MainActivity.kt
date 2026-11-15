package com.tonybenoy.smbdrop.ui

import android.Manifest
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.data.BackupSource
import com.tonybenoy.smbdrop.data.TState
import kotlinx.coroutines.runBlocking

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        val locked = runBlocking { app.prefs.get().biometricLock }
        applySecure(locked)
        unlocked = !locked
        if (locked) authenticate()
        setContent {
            val vm: AppVm = viewModel()
            val s by vm.settings.collectAsState()
            SmbTheme(s.theme, s.glass, s.language) {
                if (!unlocked) LockScreen { authenticate() } else Root(vm, onLockChanged = { applySecure(runBlocking { app.prefs.get().biometricLock }) })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (runBlocking { (application as App).prefs.get().biometricLock }) unlocked = false
    }

    override fun onStart() { super.onStart(); if (!unlocked) authenticate() }

    private fun applySecure(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun authenticate() {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { unlocked = true; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock SMB Drop").setAllowedAuthenticators(auth).build())
    }
}

@Composable
fun LockScreen(onRetry: () -> Unit) {
    Aurora {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", fontSize = 48.sp); Text(tr("SMB Drop is locked"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp)); Button(onRetry, shape = Pill) { Text(tr("Unlock")) }
        }
    }
}

@Composable
fun Root(vm: AppVm, onLockChanged: () -> Unit) {
    val s by vm.settings.collectAsState(); val dests by vm.destinations.collectAsState(); val open by vm.open.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf(false) }
    var skipped by rememberSaveable { mutableStateOf(false) }
    var tutorial by rememberSaveable { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(s.onboarded, s.tutorialSeen) { if (s.onboarded && !s.tutorialSeen) tutorial = true }
    val needsOnboarding = !s.onboarded && dests.isEmpty() && !skipped

    // Android 13+: ask for notifications once
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33 && !androidx.core.app.NotificationManagerCompat.from(ctx0).areNotificationsEnabled()) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    if (needsOnboarding) { Onboarding(vm, onSkip = { skipped = true }); return }
    if (tutorial) { Tutorial { tutorial = false; scope.launch { vm.prefs.setTutorialSeen(true) } }; return }

    Box(Modifier.fillMaxSize()) {
        Aurora {
            val reduce = rememberReduceMotion()
            androidx.compose.animation.AnimatedContent(tab, transitionSpec = {
                if (reduce) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            }, label = "tab") { t ->
                when (t) {
                    0 -> HomeScreen(vm, { queue = true }, { tab = 3 })
                    1 -> BrowseScreen(vm) { tab = 3 }
                    2 -> BackupScreen(vm)
                    else -> SettingsScreen(vm, onLockChanged) { tutorial = true }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (open.isNotEmpty()) TransferPill(open, { queue = true })
            GlassNavBar(tab, { tab = it })
        }
    }
    if (queue) QueueSheet(vm) { queue = false }
}

@Composable
fun Onboarding(vm: AppVm, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Aurora {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.Center) {
            when (step) {
                0 -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(tr("SMB Drop"), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr("Send files to your NAS or PC from any app, back up photos automatically, and open your share anywhere Android shows a file picker."), style = MaterialTheme.typography.bodyLarge)
                    Button({ step = 1 }, shape = Pill) { Text(tr("Get started")) }
                    TextButton(onSkip) { Text(tr("Skip for now")) }
                }
                1 -> Box(Modifier.padding(12.dp).glass().fillMaxWidth()) {
                    AddServerFlow(vm, null) { id -> if (id == null) step = 0 else step = 2 }
                }
                else -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(tr("Back up your camera?"), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr("Photos and videos go to Photos/year/month on your home Wi-Fi. Only new photos from now on."), style = MaterialTheme.typography.bodyLarge)
                    Button({
                        perm.launch(mediaPermissions())
                        val d = vm.destinations.value.firstOrNull()
                        if (d != null) vm.saveBackup(BackupSource(label = "Camera", type = "CAMERA", destinationId = d.id, onlyNewFrom = System.currentTimeMillis() / 1000))
                        scope.launch { vm.prefs.setOnboarded(true) }
                    }, shape = Pill) { Text(tr("Turn on")) }
                    TextButton({ scope.launch { vm.prefs.setOnboarded(true) } }) { Text(tr("Not now")) }
                }
            }
        }
    }
}
