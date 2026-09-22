package com.lef.pmaaobd.prefs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.lef.pmaaobd.datastore.UserPreference
import com.lef.pmaaobd.stats.App
import com.lef.pmaaobd.stats.CreditsFragment
import com.lef.pmaaobd.stats.R
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar!!.setDisplayUseLogoEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_fragment, SettingsFragment())
                .commit()
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    super.onFragmentResumed(fm, f)
                    supportActionBar!!.subtitle = when (f) {
                        is SettingsDashboard -> {
                            resources.getString(
                                R.string.pref_data_element_settings,
                                f.dashboardIndex() + 1
                            )
                        }

                        is SettingsPIDFragment -> {
                            f.requireArguments().getCharSequence("title")
                        }

                        else -> {
                            null
                        }
                    }
                    supportActionBar!!.setDisplayHomeAsUpEnabled(f !is SettingsFragment)
                }
            }, false
        )
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        // Handle item selection
        return when (item.itemId) {
            R.id.action_preview -> {
                launchFragment("preview", DashboardPreviewFragment())
                true
            }

            R.id.action_export_dashboards -> {
                // Export the file to a user provided location
                exportFile()
                true
            }

            R.id.action_import_dashboards -> {
                openFile()
                true
            }

            R.id.action_copy_logs -> {
                logsToClipboard()
                true
            }

            R.id.action_credits -> {
                launchFragment("credits", CreditsFragment())
                true
            }

            R.id.action_obd_adapter -> {
                startActivity(Intent(this, ObdAdapterActivity::class.java))
                true
            }

            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openFile() {
        importFileLauncher.launch(EXPORT_MIME)
    }

    private fun exportFile() {
        exportFileLauncher.launch("")
    }

    private fun launchFragment(
        tag: String,
        fragment: Fragment
    ) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing?.isVisible == true) return
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(
                R.id.settings_fragment,
                existing ?: fragment,
                tag
            )
            .addToBackStack(null)
            .commit()
    }

    private val exportFileLauncher = registerForActivityResult(ExportFileContract()) { uri: Uri? ->
        // This lambda will be executed when the activity result returns
        lifecycleScope.launch(Dispatchers.IO) {
            val data = applicationContext.dataStore.data.first()
            val result = if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    UserPreferenceSerializer.writeTo(data, outputStream)
                }
                R.string.file_exported_successfully
            } else {
                R.string.export_failed
            }
            runOnUiThread {
                Toast.makeText(baseContext, result, Toast.LENGTH_SHORT).show()
            }
        }
    }


    private val importFileLauncher = registerForActivityResult(ImportFileContract()) { uri: Uri? ->
        if (uri != null) {
            val inStream = contentResolver.openInputStream(uri)
            this@SettingsActivity.lifecycleScope.launch {
                try {
                    this@SettingsActivity.applicationContext.dataStore.updateData {
                        return@updateData UserPreference.parseFrom(inStream)
                    }
                } catch (e: InvalidProtocolBufferException) {
                    Toast.makeText(
                        baseContext,
                        R.string.invalid_settings,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    class ExportFileContract : ActivityResultContract<String, Uri?>() {

        override fun createIntent(context: Context, input: String): Intent {
            return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = EXPORT_MIME
                // Suggest a file name based on the original file name
                putExtra(Intent.EXTRA_TITLE, "dashboards.pb")
            }
        }

        // This function parses the activity result and returns the URI of the user selected location
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            return if (resultCode == Activity.RESULT_OK) {
                intent?.data
            } else {
                null
            }
        }
    }

    class ImportFileContract : ActivityResultContract<String, Uri?>() {

        override fun createIntent(context: Context, input: String): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = input
            }
        }

        // This function parses the activity result and returns the URI of the user selected location
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            return if (resultCode == Activity.RESULT_OK) {
                intent?.data
            } else {
                null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val permissionsToRequest: MutableList<String> = ArrayList()
        if (ContextCompat.checkSelfPermission(this, PERMISSION_CAR_VENDOR_EXTENSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(PERMISSION_CAR_VENDOR_EXTENSION)
        }
        val btPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        btPerms.filterTo(permissionsToRequest) {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }

    }


    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        // Instantiate the new Fragment
        val args = Bundle()
        args.putCharSequence("title", pref.title)
        val prefix = pref.extras.getString("prefix") ?: pref.key
        if (prefix.startsWith("clock_") || prefix.startsWith("display")) {
            val parts = prefix.split("_")
            assert(parts.size == 3)
            val isClock = parts[0] == "clock"
            val screen = parts[1].toInt()
            val index = parts[2].toInt()
            args.putBoolean("isClock", isClock)
            args.putInt("screen", screen)
            args.putInt("index", index)
        }
        args.putString("prefix", prefix)
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment!!
        )
        fragment.arguments = args
        // Replace the existing Fragment with the new Fragment
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(R.id.settings_fragment, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    private fun logsToClipboard() {
        val logs = (application as App).logTree.logToString()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Performance Monitor AAOBD log", logs.joinToString("\n")))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }


    companion object {
        private const val REQUEST_PERMISSIONS = 0
        private const val PERMISSION_CAR_VENDOR_EXTENSION =
            "com.google.android.gms.permission.CAR_VENDOR_EXTENSION"
        const val EXPORT_MIME = "application/octet-stream"
    }
}

