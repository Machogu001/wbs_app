package com.example.myapplication

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.graphics.pdf.PdfRenderer
import android.text.Html
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileInputStream

// Reads a metadata array of {"value": ..., "label": ...} objects (as returned by
// register.php for country codes, registration/customer/connection types) into a
// simple value-to-label pair list the registration UI can render option chips from.
private fun JSONArray.toOptionPairs(): List<Pair<String, String>> {
    val pairs = mutableListOf<Pair<String, String>>()
    for (index in 0 until length()) {
        val option = optJSONObject(index) ?: continue
        val value = option.optString("value")
        if (value.isBlank()) continue
        pairs.add(value to option.optString("label").ifBlank { value })
    }
    return pairs
}

private fun JSONArray.toFlexibleOptionPairs(): List<Pair<String, String>> {
 val pairs = mutableListOf<Pair<String, String>>()
 for (index in 0 until length()) {
 val option = optJSONObject(index) ?: continue
 val value = option.opt("value")?.toString().orEmpty().ifBlank {
 option.optString("key")
 }
 if (value.isBlank()) continue
 val label = option.optString("label").ifBlank {
 option.optString("title").ifBlank { value }
 }
 pairs += value to label
 }
 return pairs
}

class MainActivity : AppCompatActivity() {
    private companion object {
        private const val PREF_THEME_PREFERENCE = "theme_preference"
        private val VALID_THEME_PREFERENCES = setOf("system", "light", "dark")

        // Set right before a theme change initiated from the Profile screen is
        // applied. If AppCompatDelegate ends up recreating the Activity (the
        // effective light/dark mode changed), the fresh instance's restoreSession()
        // reads this flag to return the user to Profile instead of the Dashboard.
        private var pendingReturnToProfile = false
    }

    private lateinit var api: ApiClient
    private val preferences by lazy { getSharedPreferences("wbs_session", MODE_PRIVATE) }
    private var token: String? = null
    private var currentUser: JSONObject? = null
    private var backAction: (() -> Unit)? = null
    private data class ScreenSnapshot(
        val view: View,
        val backAction: (() -> Unit)?,
        val reload: ScreenLoad? = null
    )
    private data class ScreenLoad(val path: String, val callback: (Result<JSONObject>) -> Unit)
    private val navigationHistory = mutableListOf<ScreenSnapshot>()
    private val forwardHistory = mutableListOf<ScreenSnapshot>()
    @Volatile private var recordingScreenLoad = false
    @Volatile private var pendingScreenLoad: ScreenLoad? = null
    private var replaceOnNextShow = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private var swipeEligible = false
    private var displayedScreen: ScreenSnapshot? = null
    private var selectedPhoto: Uri? = null
    private var readingInputs: List<EditText> = emptyList()
    private var readingPhotoLabel: TextView? = null

    // Utility/finance palette: deep navy header, rich blue action, teal positive,
    // amber warning, cool-gray background. Solid tones below are contrast-checked
    // (>=4.5:1) for white text; tint/dark pairs are used for accented card surfaces.
    // Every value is resolved from values/colors.xml (light) or values-night/colors.xml
    // (dark) via ContextCompat.getColor so the whole programmatic UI stays theme-aware,
    // not just the XML theme. `by lazy` defers resolution until the context/resources
    // are attached (safe to read from onCreate) and is re-evaluated fresh whenever the
    // Activity is recreated for a theme change.
    private val navy by lazy { ContextCompat.getColor(this, R.color.brand_navy) }
    private val navyDark by lazy { ContextCompat.getColor(this, R.color.brand_navy_dark) }
    private val primary by lazy { ContextCompat.getColor(this, R.color.brand_primary) }
    private val primaryDark by lazy { ContextCompat.getColor(this, R.color.brand_primary_dark) }
    private val tealDark by lazy { ContextCompat.getColor(this, R.color.brand_accent_dark) }
    private val amberDark by lazy { ContextCompat.getColor(this, R.color.brand_amber_dark) }
    private val danger by lazy { ContextCompat.getColor(this, R.color.brand_red) }
    private val dangerDark by lazy { ContextCompat.getColor(this, R.color.brand_red_dark) }
    private val headingText by lazy { ContextCompat.getColor(this, R.color.heading_text) }
    private val headerGradientEnd by lazy { ContextCompat.getColor(this, R.color.header_gradient_end) }
    private val cardBackground by lazy { ContextCompat.getColor(this, R.color.surface_card) }
    private val cardBackgroundMuted by lazy { ContextCompat.getColor(this, R.color.surface_card_muted) }
    private val textPrimary by lazy { ContextCompat.getColor(this, R.color.body_text) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.muted_text) }
    private val border by lazy { ContextCompat.getColor(this, R.color.border) }
    private val tintBlue by lazy { ContextCompat.getColor(this, R.color.surface_tint_blue) }
    private val tintTeal by lazy { ContextCompat.getColor(this, R.color.surface_tint_teal) }
    private val tintAmber by lazy { ContextCompat.getColor(this, R.color.surface_tint_amber) }
    private val tintRed by lazy { ContextCompat.getColor(this, R.color.surface_tint_red) }
    private val tintNavy by lazy { ContextCompat.getColor(this, R.color.surface_tint_navy) }
    private data class Tone(val accent: Int?, val tint: Int, val border: Int, val onTint: Int)
    private val toneNeutral by lazy { Tone(null, cardBackgroundMuted, border, textPrimary) }
    // The accent bar/icon/text drawn on top of a tinted surface uses the "*Dark" token
    // (primaryDark rather than primary) so it stays high-contrast once the surface
    // itself becomes a dark, desaturated tint in night mode.
    private val toneBlue by lazy { Tone(primaryDark, tintBlue, tintBlue, primaryDark) }
    private val toneTeal by lazy { Tone(tealDark, tintTeal, tintTeal, tealDark) }
    private val toneAmber by lazy { Tone(amberDark, tintAmber, tintAmber, amberDark) }
    private val toneRed by lazy { Tone(dangerDark, tintRed, tintRed, dangerDark) }
    private lateinit var photoPicker: ActivityResultLauncher<Array<String>>
    private val locationPermissionRequestCode = 2101
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var screenEpoch = 0L
    private var registrationMeta: JSONObject? = null
    private var registrationDraft = RegistrationDraft()
    private var pendingLocationAction: (() -> Unit)? = null
    private var activePdfRenderer: PdfRenderer? = null
    private var activePdfDescriptor: ParcelFileDescriptor? = null

    // Draft form state for the public registration wizard. Every option toggle
    // (registration type, customer type, country code, connection type) fully
    // re-renders the screen, so entered field values are captured back into this
    // holder before each rebuild and used to repopulate the recreated inputs.
    private data class RegistrationDraft(
        var registrationType: String = "client",
        var customerType: String = "individual",
        var firstName: String = "",
        var middleName: String = "",
        var lastName: String = "",
        var companyName: String = "",
        var companyRegistrationNumber: String = "",
        var countryCode: String = "",
        var phoneLocal: String = "",
        var username: String = "",
        var idNumber: String = "",
        var email: String = "",
        var address: String = "",
        var password: String = "",
        var confirmPassword: String = "",
        var taxPin: String = "",
        var connectionType: String = "",
        var locationLabel: String = "",
        var latitude: Double? = null,
        var longitude: Double? = null,
        var gpsAccuracy: Float? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Applied before super.onCreate() so AppCompat resolves and loads this
        // Activity's initial resources (colors/insets appearance) in the right
        // light/dark mode from the very first frame - no flash, no recreation.
        applyThemePreference(preferences.getString(PREF_THEME_PREFERENCE, "system"), persist = false)
        super.onCreate(savedInstanceState)
        photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            selectedPhoto = uri
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                readingPhotoLabel?.text = fileName(it)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navigateBack()) finishAfterTransition()
            }
        })
        token = preferences.getString("access_token", null)
        api = ApiClient { token }
        api.requestObserver = { path, method, callback ->
            if (recordingScreenLoad && method == "GET" && pendingScreenLoad == null) {
                pendingScreenLoad = ScreenLoad(path, callback)
            }
        }
        if (token == null) showLogin() else restoreSession()
    }

    // ---------------------------------------------------------------------
    // Device theme adoption. theme_preference is one of "system" | "light" |
    // "dark" (matching the theme.php / me.php API contract). The last-known
    // value is persisted locally so app startup (including before login) can
    // apply it immediately; it is (re)synced from the authoritative server
    // value after login/session restore and whenever changed from the
    // Appearance section on the Profile screen.
    // ---------------------------------------------------------------------

    private fun applyThemePreference(preference: String?, persist: Boolean = true) {
        val normalized = preference?.lowercase()?.takeIf { it in VALID_THEME_PREFERENCES } ?: "system"
        if (persist) {
            preferences.edit { putString(PREF_THEME_PREFERENCE, normalized) }
        }
        val mode = mapThemePreferenceToNightMode(normalized)
        // Guard against redundant calls: AppCompatDelegate recreates every
        // affected Activity when the effective mode changes, so only invoke
        // it when the mode actually differs to avoid recreation loops.
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun currentThemePreference(): String =
        preferences.getString(PREF_THEME_PREFERENCE, "system")?.takeIf { it in VALID_THEME_PREFERENCES }
            ?: "system"

    private fun themePreferenceLabel(preference: String): String = when (preference) {
        "light" -> "Light"
        "dark" -> "Dark"
        else -> "Follow device (recommended)"
    }

    // Syncs the locally-persisted preference (and the applied night mode) with
    // whatever the server considers authoritative for this user, e.g. after
    // login or session restore. A no-op when they already match, so it never
    // triggers an unnecessary Activity recreation.
    private fun syncThemePreferenceFromServer(user: JSONObject?) {
        val serverPreference = user?.optString("theme_preference")?.takeIf { it in VALID_THEME_PREFERENCES }
            ?: return
        if (serverPreference != currentThemePreference()) {
            applyThemePreference(serverPreference)
        }
    }

    private fun isNightModeActive(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun restoreSession() {
        showLoading("Restoring session")
        api.request("me.php") { result ->
            onResult(result, "Could not restore your session") { response ->
                val data = response.data()
                currentUser = data.optJSONObject("user")
                syncThemePreferenceFromServer(currentUser)
                when {
                    currentUser?.optBoolean("must_change_password") == true -> showChangePassword(true)
                    data.optBoolean("requires_registration_payment") -> loadRegistrationPayment()
                    pendingReturnToProfile -> {
                        pendingReturnToProfile = false
                        showProfile()
                    }
                    else -> showDashboard()
                }
            }
        }
    }

    private fun showLogin() {
        navigationHistory.clear()
        forwardHistory.clear()
        displayedScreen = null
        backAction = null
        currentUser = null
        val identifier = input("Enter your account number, phone or email").apply {
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val password = input(
            "Enter your password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val login = actionButton("Sign in")
        val createAccount = secondaryButton("Create account")
        val form = screen("My Water Bill", "Secure access to your water account")
        form.addView(heroBanner(
            title = "Water account in one place",
            message = "Pay bills, track meter activity, send readings and manage service requests without using the browser.",
            eyebrow = "Mobile portal",
            tone = toneBlue,
            iconRes = R.drawable.ic_wallet,
            compact = false
        ))
        form.addView(sectionTitle("Sign in"))
        form.addView(body("Use the same details you use on the customer portal.").apply {
            setPadding(dp(2), 0, 0, dp(16))
        })
        form.addView(labeledField("Account", identifier))
        form.addView(labeledField("Password", password))
        form.addView(login)
        form.addView(createAccount)
        form.addView(loginFooter())
        createAccount.setOnClickListener { loadRegistrationMetadata() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login.performClick()
                true
            } else {
                false
            }
        }
        login.setOnClickListener {
            if (identifier.text.isBlank() || password.text.isBlank()) {
                toast("Enter your account and password")
                return@setOnClickListener
            }
            setLoading(login, true, "Sign in")
            api.request(
                "login.php",
                "POST",
                JSONObject()
                    .put("identifier", identifier.text.toString().trim())
                    .put("password", password.text.toString())
                    .put("device_name", android.os.Build.MODEL)
            ) { result ->
                runOnUiThread {
                    setLoading(login, false, "Sign in")
                    result.onSuccess { response ->
                        if (response.optString("status") == "two_factor_required") {
                            showTwoFactor(response.data())
                        } else {
                            completeLogin(response.data())
                        }
                    }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showTwoFactor(challenge: JSONObject) {
        backAction = ::showLogin
        val code = input("6-digit verification code", InputType.TYPE_CLASS_NUMBER).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
        val verify = actionButton("Verify and continue")
        val form = screen(
            "Verify your identity",
            "Enter the code sent by ${challenge.optString("method", "SMS")}."
        )
        form.addView(code)
        form.addView(verify)
        val methods = challenge.optJSONArray("available_methods") ?: JSONArray()
        for (index in 0 until methods.length()) {
            val method = methods.optString(index)
            val resend = secondaryButton("Resend by ${method.uppercase()}")
            resend.setOnClickListener {
                setLoading(resend, true, "Resend by ${method.uppercase()}")
                api.request(
                    "resend_2fa.php",
                    "POST",
                    JSONObject()
                        .put("challenge_token", challenge.optString("challenge_token"))
                        .put("method", method)
                ) { result ->
                    runOnUiThread {
                        setLoading(resend, false, "Resend by ${method.uppercase()}")
                        result.onSuccess {
                            toast(it.optString("message", "Verification code sent."))
                        }.onFailure(::handleError)
                    }
                }
            }
            form.addView(resend)
        }
        verify.setOnClickListener {
            if (code.text.length != 6) {
                toast("Enter the 6-digit code")
                return@setOnClickListener
            }
            setLoading(verify, true, "Verify and continue")
            api.request(
                "verify_2fa.php",
                "POST",
                JSONObject()
                    .put("challenge_token", challenge.optString("challenge_token"))
                    .put("code", code.text.toString())
                    .put("device_name", android.os.Build.MODEL)
            ) { result ->
                runOnUiThread {
                    setLoading(verify, false, "Verify and continue")
                    result.onSuccess { completeLogin(it.data()) }.onFailure(::handleError)
                }
            }
        }
        show(form)
        code.requestFocus()
        code.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(code, 0)
        }
    }

    private fun completeLogin(data: JSONObject) {
        val accessToken = data.optJSONObject("access")?.optString("access_token").orEmpty()
        if (accessToken.isBlank()) {
            toast("The server did not return an access token.")
            return
        }
        navigationHistory.clear()
        forwardHistory.clear()
        displayedScreen = null
        token = accessToken
        preferences.edit { putString("access_token", accessToken) }
        currentUser = data.optJSONObject("user")
        syncThemePreferenceFromServer(currentUser)
        if (currentUser?.optBoolean("must_change_password") == true) {
            showChangePassword(true)
        } else if (data.optBoolean("requires_registration_payment")) {
            loadRegistrationPayment()
        } else {
            showDashboard()
        }
    }

    // ---------------------------------------------------------------------
    // Registration-payment follow-up. Shown after login/me whenever the
    // authenticated account is not yet active. Always loads the authoritative
    // balance/status from GET registration_payment.php (rather than trusting a
    // payload captured at login time), and can resend the M-Pesa STK prompt via
    // POST registration_payment.php before handing off to the existing generic
    // payment-status poller.
    // ---------------------------------------------------------------------

    private fun loadRegistrationPayment() {
        backAction = null
        showLoading("Loading registration status")
        api.request("registration_payment.php") { result ->
            onResult(result, "Could not load your registration payment status") { response ->
                renderRegistrationPayment(response.data())
            }
        }
    }

    private fun renderRegistrationPayment(data: JSONObject) {
        if (data.optBoolean("registration_fully_settled")) {
            toast("Your registration fee is settled. Welcome!")
            restoreSession()
            return
        }
        backAction = null
        val fee = data.optDouble("registration_fee")
        val balance = data.optDouble("registration_balance")
        val billStatus = data.optString("bill_status").ifBlank { "pending" }
        val latestPayment = data.optJSONObject("latest_payment")
        val registrationPayment = data.optJSONObject("registration_payment")

        val form = screen("Registration pending", "Complete your registration fee to activate your account.")
        form.addView(statusBanner(
            "Payment required",
            "Your account will be activated automatically once the registration fee is confirmed.",
            toneAmber, R.drawable.ic_circle_clock, false
        ))
        form.addView(card("Registration fee", money(fee)))
        form.addView(card("Balance due", money(balance), if (balance > 0.01) toneAmber else toneTeal))
        form.addView(card("Bill status", billStatus.replaceFirstChar(Char::uppercase)))
        if (latestPayment != null) {
            form.addView(sectionTitle("Latest payment attempt"))
            addField(form, "Amount", money(latestPayment.optDouble("amount")))
            addField(form, "Status", latestPayment.optString("status"))
            addField(form, "Phone", latestPayment.optString("phone_number"))
            addField(form, "M-Pesa receipt", latestPayment.optString("mpesa_receipt").ifBlank { "Pending" })
        }
        registrationPayment?.let {
            addDocumentButton(form, "Open registration proforma", it.optString("document_url"))
        }
        form.addView(sectionTitle("Resend M-Pesa prompt"))
        form.addView(body("Optionally enter a different phone number, or leave blank to use your account phone number.").apply {
            setPadding(dp(2), 0, 0, dp(10))
        })
        val phone = input("M-Pesa phone number (optional)", InputType.TYPE_CLASS_PHONE)
        val resend = actionButton("Resend M-Pesa prompt")
        form.addView(phone)
        form.addView(resend)
        resend.setOnClickListener {
            setLoading(resend, true, "Resend M-Pesa prompt")
            val body = JSONObject().put("action", "resend_stk")
            phone.text.toString().trim().takeIf(String::isNotBlank)?.let { body.put("phone", it) }
            api.request("registration_payment.php", "POST", body) { result ->
                runOnUiThread {
                    setLoading(resend, false, "Resend M-Pesa prompt")
                    result.onSuccess { response ->
                        toast(response.optString("message", "Registration payment initiated."))
                        val payment = response.data().optJSONObject("payment")
                        val billId = payment?.optInt("bill_id") ?: 0
                        val paymentId = payment?.optInt("payment_id")?.takeIf { it > 0 }
                        val checkoutRequestId = payment?.optString("checkout_request_id")?.takeIf(String::isNotBlank)
                        showPaymentStatus(billId, paymentId, checkoutRequestId)
                    }.onFailure(::handleError)
                }
            }
        }
        val refresh = secondaryButton("Refresh")
        refresh.setOnClickListener { loadRegistrationPayment() }
        form.addView(refresh)
        form.addView(signOutButton())
        show(form)
    }

    // ---------------------------------------------------------------------
    // Public registration ("Create account"). Metadata drives every option so
    // the app never assumes display labels or hard-codes server-side choices:
    // GET register.php returns the registration fee, GPS enforcement flag and
    // threshold, and the value/label option lists for registration type,
    // customer type and connection type, plus the active country dial codes.
    // The whole screen re-renders on every mode toggle (registration type,
    // customer type, country code, connection type) — entered field values
    // are captured back into registrationDraft immediately beforehand so nothing
    // typed so far is lost across the rebuild.
    // ---------------------------------------------------------------------

    private fun loadRegistrationMetadata() {
        backAction = ::showLogin
        showLoading("Loading registration form")
        api.request("register.php") { result ->
            onResult(result, "Could not load the registration form") { response ->
                val data = response.data()
                registrationMeta = data
                val draft = registrationDraft
                data.optJSONArray("country_code_options")?.toOptionPairs()?.let { options ->
                    if (options.isNotEmpty() && options.none { it.first == draft.countryCode }) {
                        draft.countryCode = options.first().first
                    }
                }
                data.optJSONArray("connection_type_options")?.toOptionPairs()?.let { options ->
                    if (options.isNotEmpty() && options.none { it.first == draft.connectionType }) {
                        draft.connectionType = options.first().first
                    }
                }
                data.optJSONArray("registration_type_options")?.toOptionPairs()?.let { options ->
                    if (options.isNotEmpty() && options.none { it.first == draft.registrationType }) {
                        draft.registrationType = options.first().first
                    }
                }
                data.optJSONArray("customer_type_options")?.toOptionPairs()?.let { options ->
                    if (options.isNotEmpty() && options.none { it.first == draft.customerType }) {
                        draft.customerType = options.first().first
                    }
                }
                showRegistrationForm()
            }
        }
    }

    private fun showRegistrationForm() {
        val meta = registrationMeta
        if (meta == null) {
            loadRegistrationMetadata()
            return
        }
        backAction = ::showLogin
        val draft = registrationDraft
        val registrationFee = meta.optDouble("registration_fee", 0.0)
        val enforceLocation = meta.optBoolean("enforce_location_accuracy", false)
        val maxAccuracy = meta.optDouble("gps_accuracy_max_meters", 14.0)
        val companyDisplay = meta.optString("company_name").ifBlank { "WBS" }
        val registrationTypeOptions = meta.optJSONArray("registration_type_options")?.toOptionPairs().orEmpty()
        val customerTypeOptions = meta.optJSONArray("customer_type_options")?.toOptionPairs().orEmpty()
        val countryOptions = meta.optJSONArray("country_code_options")?.toOptionPairs().orEmpty()
        val connectionOptions = meta.optJSONArray("connection_type_options")?.toOptionPairs().orEmpty()

        val isStaff = draft.registrationType == "staff"
        val isCompany = draft.customerType == "company"

        // Declared up-front so listeners wired further below (including the
        // option-toggle handlers added first) can close over them.
        val firstName = input(if (isCompany && !isStaff) "Contact first name" else "First name").apply { setText(draft.firstName) }
        val middleName = input("Middle name (optional)").apply { setText(draft.middleName) }
        val lastName = input(if (isCompany && !isStaff) "Contact last name" else "Last name").apply { setText(draft.lastName) }
        val companyName = input("Company / organization name").apply { setText(draft.companyName) }
        val companyRegNumber = input("Company registration number").apply { setText(draft.companyRegistrationNumber) }
        val phoneLocal = input("Phone number (without country code)", InputType.TYPE_CLASS_PHONE).apply { setText(draft.phoneLocal) }
        val username = input("Username").apply { setText(draft.username) }
        val idNumber = input(if (isStaff) "ID number (optional)" else "ID number").apply { setText(draft.idNumber) }
        val email = input("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply { setText(draft.email) }
        val address = input("Address").apply { setText(draft.address) }
        val taxPin = input("Tax PIN (optional)").apply { setText(draft.taxPin) }
        val password = input("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { setText(draft.password) }
        val confirmPassword = input("Confirm password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { setText(draft.confirmPassword) }
        val locationLabel = input("Location label (plus code, landmark, etc.)").apply { setText(draft.locationLabel) }

        fun captureFieldsInto(target: RegistrationDraft) {
            target.firstName = firstName.text.toString()
            target.middleName = middleName.text.toString()
            target.lastName = lastName.text.toString()
            target.companyName = companyName.text.toString()
            target.companyRegistrationNumber = companyRegNumber.text.toString()
            target.phoneLocal = phoneLocal.text.toString()
            target.username = username.text.toString()
            target.idNumber = idNumber.text.toString()
            target.email = email.text.toString()
            target.address = address.text.toString()
            target.taxPin = taxPin.text.toString()
            target.password = password.text.toString()
            target.confirmPassword = confirmPassword.text.toString()
            target.locationLabel = locationLabel.text.toString()
        }

        val form = screen("Create account", "Register for $companyDisplay water services")
        form.addView(sectionTitle("Account type"))
        form.addView(optionGroup("I am registering as", registrationTypeOptions, draft.registrationType, 2) { value ->
            captureFieldsInto(draft)
            draft.registrationType = value
            showRegistrationForm()
        })
        if (!isStaff) {
            form.addView(optionGroup("Customer type", customerTypeOptions, draft.customerType, 2) { value ->
                captureFieldsInto(draft)
                draft.customerType = value
                showRegistrationForm()
            })
        }

        form.addView(sectionTitle(if (isCompany && !isStaff) "Company details" else "Personal details"))
        if (isCompany && !isStaff) {
            form.addView(labeledField("Company / organization name", companyName))
            form.addView(labeledField("Company registration number", companyRegNumber))
        }
        form.addView(labeledField(if (isCompany && !isStaff) "Contact first name" else "First name", firstName))
        form.addView(labeledField("Middle name (optional)", middleName))
        form.addView(labeledField(if (isCompany && !isStaff) "Contact last name" else "Last name", lastName))

        form.addView(sectionTitle("Contact"))
        form.addView(pickerField(countryOptions, draft.countryCode) { value ->
            captureFieldsInto(draft)
            draft.countryCode = value
            showRegistrationForm()
        })
        form.addView(labeledField("Phone number", phoneLocal))

        if (isStaff) {
            form.addView(sectionTitle("Staff account"))
            form.addView(labeledField("Username", username))
            form.addView(labeledField("ID number (optional)", idNumber))
        } else {
            form.addView(labeledField("Email", email))
            form.addView(labeledField("Address", address))
            if (!isCompany) {
                form.addView(labeledField("ID number", idNumber))
            }
            form.addView(labeledField("Tax PIN (optional)", taxPin))

            form.addView(sectionTitle("Connection"))
            form.addView(optionGroup("Connection type", connectionOptions, draft.connectionType, connectionOptions.size.coerceIn(1, 3)) { value ->
                captureFieldsInto(draft)
                draft.connectionType = value
                showRegistrationForm()
            })

            form.addView(sectionTitle("Location"))
            form.addView(body(
                if (enforceLocation)
                    "GPS location is required (accuracy within ${maxAccuracy.toInt()}m). Go outdoors and tap capture below."
                else
                    "Optional: capture your GPS location to help our field teams find your connection."
            ).apply { setPadding(dp(2), 0, 0, dp(10)) })
            form.addView(labeledField("Location label", locationLabel))
            val captureButton = secondaryButton("Capture current location")
            val locationStatus = body(locationStatusText(draft, enforceLocation, maxAccuracy)).apply {
                setPadding(dp(2), dp(2), dp(2), dp(14))
            }
            captureButton.setOnClickListener {
                captureFieldsInto(draft)
                setLoading(captureButton, true, "Capture current location")
                captureCurrentLocation { lat, lng, accuracy, error ->
                    runOnUiThread {
                        setLoading(captureButton, false, "Capture current location")
                        if (error != null) {
                            toast(error)
                            return@runOnUiThread
                        }
                        draft.latitude = lat
                        draft.longitude = lng
                        draft.gpsAccuracy = accuracy
                        val accuracyOk = accuracy != null && accuracy <= maxAccuracy
                        toast(
                            if (enforceLocation && !accuracyOk)
                                "Captured accuracy ${accuracy?.let { "%.0f".format(Locale.getDefault(), it) } ?: "unknown"}m exceeds the required ${maxAccuracy.toInt()}m. Move to an open area and try again."
                            else
                                "Location captured (±${accuracy?.let { "%.0f".format(Locale.getDefault(), it) } ?: "unknown"}m)."
                        )
                        showRegistrationForm()
                    }
                }
            }
            form.addView(captureButton)
            form.addView(locationStatus)

            if (registrationFee > 0) {
                form.addView(card("Registration fee", money(registrationFee), toneAmber))
                form.addView(body("An M-Pesa STK prompt for this amount will be sent to your phone once you submit.").apply {
                    setPadding(dp(2), dp(2), dp(2), dp(10))
                })
            }

            addRegistrationTermsSection(form, meta)

            form.addView(sectionTitle("Password"))
            form.addView(labeledField("Password", password))
            form.addView(labeledField("Confirm password", confirmPassword))
        }

        val submitLabel = if (isStaff) "Create staff account" else "Create account"
        val submit = actionButton(submitLabel)
        form.addView(submit)
        val signInInstead = secondaryButton("Already have an account? Sign in")
        signInInstead.setOnClickListener { showLogin() }
        form.addView(signInInstead)
        addBack(form)

        submit.setOnClickListener {
            captureFieldsInto(draft)
            val validationError = validateRegistrationDraft(draft, enforceLocation, maxAccuracy)
            if (validationError != null) {
                toast(validationError)
                return@setOnClickListener
            }
            setLoading(submit, true, submitLabel)
            api.request("register.php", "POST", buildRegistrationBody(draft)) { result ->
                runOnUiThread {
                    setLoading(submit, false, submitLabel)
                    result.onSuccess { response -> showRegistrationResult(response.data()) }
                        .onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun addRegistrationTermsSection(parent: LinearLayout, meta: JSONObject) {
        val terms = meta.optJSONObject("terms_conditions") ?: JSONObject()
        val preferredFormat = terms.optString("preferred_format").ifBlank { "sections" }
        val sections = terms.optJSONArray("sections")
        val html = terms.optString("html").ifBlank { meta.optString("terms_conditions_html") }
        val text = terms.optString("text").ifBlank {
            terms.optString("content").ifBlank { meta.optString("terms_conditions_content") }
        }
        parent.addView(sectionPanel(
            title = "Terms & conditions",
            description = "Review the current service terms before submitting your registration."
        ) {
            when {
                preferredFormat == "sections" && sections != null && sections.length() > 0 -> {
                    for (index in 0 until sections.length()) {
                        val section = sections.optJSONObject(index) ?: continue
                        val heading = section.optString("title")
                        val content = section.optString("content")
                        if (heading.isBlank() && content.isBlank()) continue
                        addView(card(heading.ifBlank { "Terms & Conditions" }, content))
                    }
                }
                html.isNotBlank() -> {
                    addView(TextView(this@MainActivity).apply {
                        this.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                        textSize = 16f
                        setTextColor(textPrimary)
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        background = roundedDrawable(cardBackground, border, 14f)
                        setTextIsSelectable(true)
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(14) })
                }
                text.isNotBlank() -> addView(card("Terms & Conditions", text))
                else -> addView(empty("Terms and conditions are not available right now."))
            }
        })
    }

    private fun validateRegistrationDraft(
        draft: RegistrationDraft,
        enforceLocation: Boolean,
        maxAccuracy: Double
    ): String? {
        val isStaff = draft.registrationType == "staff"
        val isCompany = draft.customerType == "company"
        if (draft.firstName.isBlank() || draft.lastName.isBlank()) {
            return "Enter first name and last name."
        }
        if (isCompany && !isStaff && (draft.companyName.isBlank() || draft.companyRegistrationNumber.isBlank())) {
            return "Enter the company name and company registration number."
        }
        if (draft.countryCode.isBlank() || draft.phoneLocal.isBlank()) {
            return "Select a country code and enter your phone number."
        }
        if (isStaff) {
            if (draft.username.isBlank()) {
                return "Enter a username for the staff account."
            }
            if (!Regex("^[A-Za-z0-9._-]{3,30}$").matches(draft.username)) {
                return "Username must be 3-30 characters using letters, numbers, dot, underscore or hyphen."
            }
        } else {
            if (!isCompany && draft.idNumber.isBlank()) {
                return "Enter your ID number."
            }
            if (draft.email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(draft.email).matches()) {
                return "Enter a valid email address."
            }
            if (draft.address.isBlank()) {
                return "Enter your address."
            }
            if (draft.password.length < 6) {
                return "Enter a password with at least 6 characters."
            }
            if (draft.password != draft.confirmPassword) {
                return "Password and confirmation do not match."
            }
            if (enforceLocation) {
                if (draft.locationLabel.isBlank()) {
                    return "Enter a location label."
                }
                val accuracy = draft.gpsAccuracy
                if (draft.latitude == null || draft.longitude == null || accuracy == null || accuracy > maxAccuracy) {
                    return "Capture your GPS location (accuracy within ${maxAccuracy.toInt()}m) before submitting."
                }
            }
        }
        return null
    }

    private fun buildRegistrationBody(draft: RegistrationDraft): JSONObject {
        val body = JSONObject()
            .put("registration_type", draft.registrationType)
            .put("customer_type", draft.customerType)
            .put("first_name", draft.firstName.trim())
            .put("middle_name", draft.middleName.trim())
            .put("last_name", draft.lastName.trim())
            .put("phone_country_code", draft.countryCode)
            .put("phone_number_local", draft.phoneLocal.trim())
        if (draft.registrationType == "staff") {
            body.put("username", draft.username.trim())
            if (draft.idNumber.isNotBlank()) body.put("id_number", draft.idNumber.trim())
            return body
        }
        if (draft.customerType == "company") {
            body.put("company_name", draft.companyName.trim())
            body.put("company_registration_number", draft.companyRegistrationNumber.trim())
        }
        if (draft.idNumber.isNotBlank()) body.put("id_number", draft.idNumber.trim())
        body.put("email", draft.email.trim())
        body.put("address", draft.address.trim())
        body.put("password", draft.password)
        if (draft.taxPin.isNotBlank()) body.put("tax_pin", draft.taxPin.trim())
        body.put("connection_type", draft.connectionType)
        if (draft.locationLabel.isNotBlank()) body.put("location_label", draft.locationLabel.trim())
        draft.latitude?.let { body.put("latitude", it) }
        draft.longitude?.let { body.put("longitude", it) }
        draft.gpsAccuracy?.let { body.put("gps_accuracy", it.toDouble()) }
        return body
    }

    private fun showRegistrationResult(data: JSONObject) {
        backAction = ::showLogin
        val registrationType = data.optString("registration_type")
        val accountNumber = data.optString("account_number")
        val form = screen("Registration complete", "")
        when {
            registrationType == "staff" -> {
                form.addView(statusBanner(
                    "Staff account created",
                    "Save these details now — the temporary password is shown only once.",
                    toneTeal, R.drawable.ic_circle_check, false
                ))
                form.addView(card("Account number", accountNumber))
                form.addView(card("Username", data.optString("username")))
                form.addView(card("Temporary password", data.optString("temp_password"), toneAmber))
                form.addView(body("Sign in with these details and change your password immediately from your profile.").apply {
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                })
            }
            data.optBoolean("requires_payment") -> {
                form.addView(statusBanner(
                    "Registration submitted",
                    "An M-Pesa prompt has been sent to your phone.",
                    toneAmber, R.drawable.ic_circle_clock, false
                ))
                form.addView(card("Account number", accountNumber))
                form.addView(card("Amount due", money(data.optDouble("amount"))))
                form.addView(body(
                    "Enter your M-Pesa PIN on your phone to approve the ${money(data.optDouble("amount"))} " +
                        "registration fee. Since this is a new account, sign in to follow the live payment " +
                        "status and confirm once your account is active."
                ).apply { setPadding(dp(2), dp(6), dp(2), dp(6)) })
            }
            else -> {
                form.addView(statusBanner(
                    "Account created",
                    "Your account is ready to use.",
                    toneTeal, R.drawable.ic_circle_check, false
                ))
                form.addView(card("Account number", accountNumber))
                form.addView(body("Sign in with your account number, phone, email or password to get started."))
            }
        }
        val signIn = actionButton("Sign in")
        signIn.setOnClickListener {
            registrationDraft = RegistrationDraft()
            registrationMeta = null
            showLogin()
        }
        form.addView(signIn)
        show(form)
    }

    private fun locationStatusText(draft: RegistrationDraft, enforceLocation: Boolean, maxAccuracy: Double): String {
        val lat = draft.latitude
        val lng = draft.longitude
        if (lat == null || lng == null) {
            return if (enforceLocation)
                "No location captured yet. GPS accuracy must be within ${maxAccuracy.toInt()}m."
            else
                "No location captured yet."
        }
        val accuracy = draft.gpsAccuracy
        val accuracyText = accuracy?.let { "%.0f".format(Locale.getDefault(), it) } ?: "unknown"
        val tooCoarse = enforceLocation && (accuracy == null || accuracy > maxAccuracy)
        return "Captured: %.6f, %.6f (±%sm)%s".format(
            Locale.getDefault(), lat, lng, accuracyText,
            if (tooCoarse) " — accuracy too low, recapture outdoors" else ""
        )
    }

    // ---------------------------------------------------------------------
    // GPS capture using the platform LocationManager only (no extra dependency).
    // Requests runtime permission if needed, keeping a single pending action so
    // the capture safely resumes from onRequestPermissionsResult. Never invents
    // coordinates: every outcome is either a real provider fix or an explicit
    // error/timeout passed back to the caller.
    // ---------------------------------------------------------------------

    private fun captureCurrentLocation(onResult: (Double?, Double?, Float?, String?) -> Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            pendingLocationAction = { captureCurrentLocation(onResult) }
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                locationPermissionRequestCode
            )
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onResult(null, null, null, "Location service is unavailable on this device.")
            return
        }
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            onResult(null, null, null, "Turn on device location (GPS) and try again.")
            return
        }
        var completed = false
        val cancellationSignal = CancellationSignal()
        fun deliver(location: Location?) {
            if (completed) return
            completed = true
            cancellationSignal.cancel()
            if (location == null) {
                onResult(null, null, null, "Could not get a GPS fix. Move outdoors and try again.")
            } else {
                onResult(
                    location.latitude,
                    location.longitude,
                    if (location.hasAccuracy()) location.accuracy else null,
                    null
                )
            }
        }
        try {
            locationManager.getCurrentLocation(provider, cancellationSignal, mainExecutor, ::deliver)
        } catch (_: SecurityException) {
            onResult(null, null, null, "Location permission is required to capture GPS.")
            return
        }
        mainHandler.postDelayed({
            if (!completed) {
                completed = true
                cancellationSignal.cancel()
                onResult(null, null, null, "Could not get a GPS fix. Move outdoors and try again.")
            }
        }, 20_000L)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode) {
            val granted = grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            val action = pendingLocationAction
            pendingLocationAction = null
            if (granted) {
                action?.invoke()
            } else {
                toast("Location permission is required to capture GPS coordinates.")
            }
        }
    }

    // Renders a labeled group of selectable option chips (value/label pairs
    // from server metadata), wrapped into rows of `perRow` so any number of
    // options — from a 2-way toggle to a long country-code list — lays out
    // cleanly without needing a spinner dependency.
    private fun optionGroup(
        label: String,
        options: List<Pair<String, String>>,
        selectedValue: String,
        perRow: Int,
        onSelect: (String) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(dp(2), 0, 0, dp(7))
        })
        if (options.isEmpty()) {
            addView(body("No options available.").apply { setPadding(dp(2), 0, 0, 0) })
            return@apply
        }
        val columns = perRow.coerceAtLeast(1)
        options.chunked(columns).forEach { rowOptions ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                rowOptions.forEachIndexed { index, (value, optionLabel) ->
                    addView(
                        optionChip(optionLabel, value == selectedValue) { onSelect(value) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) marginStart = dp(8)
                        }
                    )
                }
                repeat(columns - rowOptions.size) {
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
                }
            })
        }
    }

    private fun optionChip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (selected) Color.WHITE else textPrimary)
        gravity = Gravity.CENTER
        minHeight = dp(46)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = roundedDrawable(if (selected) primary else cardBackground, if (selected) primary else border, 12f)
        elevation = if (selected) dp(1).toFloat() else 0f
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    // Tap-to-pick field for long option lists (e.g. ~195 country dial codes)
    // where inline chips would be unwieldy. Opens a native single-choice
    // AlertDialog listing every server-provided label, so no assumed labels
    // and no spinner dependency are needed.
    private fun pickerField(
        options: List<Pair<String, String>>,
        selectedValue: String,
        onSelect: (String) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.country_code_label)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(dp(2), 0, 0, dp(7))
        })
        val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second
            ?: getString(R.string.select_country_code)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            background = roundedDrawable(cardBackground, border, 14f)
            setPadding(dp(16), dp(4), dp(14), dp(4))
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = selectedLabel
                textSize = 16f
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconView(R.drawable.ic_chevron_right, muted, 18))
            setOnClickListener {
                if (options.isEmpty()) return@setOnClickListener
                val labels = options.map { it.second }.toTypedArray()
                val checkedIndex = options.indexOfFirst { it.first == selectedValue }.coerceAtLeast(0)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.country_code_label)
                    .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                        dialog.dismiss()
                        onSelect(options[which].first)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
    }

    private fun showDashboard() {
        backAction = null
        showLoading("Loading account")
        api.request("dashboard.php") { result ->
            onResult(result, "Could not load dashboard") { response ->
                renderDashboard(response.data())
            }
        }
    }

    private fun renderDashboard(data: JSONObject) {
        val summary = data.optJSONObject("summary") ?: JSONObject()
        val screen = data.optJSONObject("screen") ?: JSONObject()
        val user = currentUser
        val name = user?.optString("full_name")?.takeIf(String::isNotBlank) ?: "customer"
        val latestBillsSection = screenSection(screen, "latest_bills")
        val latestPaymentSection = screenSection(screen, "latest_payment")
        val metersSection = screenSection(screen, "meters")
        val form = screen(screen.optString("title").ifBlank { "My Water Bill" }, "Welcome back")
        val outstanding = summary.optDouble("outstanding_amount")
        val heroMessage = buildString {
            append(if (outstanding > 0.01) "Outstanding balance ${money(outstanding)}" else "Your account is up to date")
            user?.optString("account_number")?.takeIf(String::isNotBlank)?.let {
                append(" • Account ")
                append(it)
            }
        }
        form.addView(heroBanner(
            title = "Hello, ${name.replaceFirstChar(Char::uppercase)}",
            message = heroMessage,
            eyebrow = user?.optString("role")?.replaceFirstChar(Char::uppercase).orEmpty().ifBlank { "Customer" },
            tone = if (outstanding > 0.01) toneAmber else toneTeal,
            iconRes = if (outstanding > 0.01) R.drawable.ic_wallet else R.drawable.ic_circle_check,
            compact = true
        ))
        form.addView(spotlightPanel(
            title = if (outstanding > 0.01) "Balance needs attention" else "Account in good standing",
            message = if (outstanding > 0.01) {
                "Review your latest bills, payment activity and meter usage from one place."
            } else {
                "Stay on top of statements, readings and support updates from your dashboard."
            },
            meta = listOfNotNull(
                user?.optString("account_number").orEmpty().takeIf(String::isNotBlank),
                summary.optInt("active_meters").takeIf { it > 0 }?.let { "$it active meter(s)" },
                summary.optInt("pending_bills").takeIf { it > 0 }?.let { "$it pending bill(s)" }
                    ?: "No pending bills"
            ).joinToString(" • "),
            tone = if (outstanding > 0.01) toneAmber else toneBlue
        ))
        form.addView(sectionPanel(
            title = "Signed in",
            description = "Keep your account details and session controls close to the top of the dashboard."
        ) {
            addView(identityCard(
                name,
                user?.optString("account_number").orEmpty(),
                user?.optString("role", "customer").orEmpty().replaceFirstChar(Char::uppercase)
            ))
            addView(buttonRow(
                "Profile" to ::showProfile,
                "Sign out" to ::performSignOut
            ))
        })
        val primaryAction = mobileActionSpec(screen.optJSONObject("primary_action"))
        val secondaryActions = (screen.optJSONArray("secondary_actions") ?: JSONArray())
            .let { actions ->
                buildList {
                    for (index in 0 until actions.length()) {
                        val action = mobileActionSpec(actions.optJSONObject(index)) ?: continue
                        if (action.first.equals("Profile", ignoreCase = true)) continue
                        add(action)
                    }
                }
            }
        if (primaryAction != null || secondaryActions.isNotEmpty()) {
            form.addView(sectionPanel(
                title = "Recommended next step",
                description = "Use the server-guided actions for the quickest route through your account tasks."
            ) {
                primaryAction?.let { (label, action) ->
                    addView(actionButton(label).apply {
                        setOnClickListener { action() }
                    })
                }
                if (secondaryActions.isNotEmpty()) {
                    addView(buttonRow(*secondaryActions.toTypedArray()))
                }
            })
        }
        val summaryCards = summaryCardViews(summary, screen.optJSONArray("summary_cards"))
        val accountSummaryCards = summaryCards.ifEmpty {
            listOf(
                summaryCard(
                    "Outstanding",
                    money(summary.optDouble("outstanding_amount")),
                    R.drawable.ic_wallet,
                    if (summary.optDouble("outstanding_amount") > 0.01) toneAmber else toneTeal
                ),
                summaryCard(
                    "Pending bills",
                    summary.optInt("pending_bills").toString(),
                    R.drawable.ic_receipt,
                    toneBlue
                ),
                summaryCard(
                    "Overdue bills",
                    summary.optInt("overdue_bills").toString(),
                    R.drawable.ic_warning_triangle,
                    if (summary.optInt("overdue_bills") > 0) toneRed else toneTeal
                ),
                summaryCard(
                    "Active meters",
                    summary.optInt("active_meters").toString(),
                    R.drawable.ic_meter,
                    toneTeal
                )
            )
        }
        form.addView(sectionPanel(
            title = "Account summary",
            description = "A quick financial snapshot in the same blue-and-gold tone as the web dashboard."
        ) {
            addView(summaryCardGrid(accountSummaryCards))
        })
        form.addView(sectionTitle(latestBillsSection?.optString("title").orEmpty().ifBlank { "Latest bills" }))
        addRecordList(
            parent = form,
            heading = latestBillsSection?.optString("title").orEmpty().ifBlank { "Latest bills" },
            rows = data.optJSONArray("latest_bills"),
            titleFields = listOf("type_label", "billing_month"),
            onClick = { row -> showBill(row.optInt("id")) },
            emptyState = latestBillsSection?.optString("empty_state").orEmpty().ifBlank { "No bills available yet." },
            showHeading = false
        )
        val latestPayment = data.optJSONObject("latest_payment")
        form.addView(sectionTitle(latestPaymentSection?.optString("title").orEmpty().ifBlank { "Latest payment" }))
        if (latestPayment == null) {
            form.addView(empty(latestPaymentSection?.optString("empty_state").orEmpty().ifBlank { "No payment has been recorded yet." }))
        } else {
            val paymentCard = card(
                money(latestPayment.optDouble("amount")),
                listOf(
                    latestPayment.optString("status"),
                    latestPayment.optString("payment_method", "M-Pesa"),
                    latestPayment.optString("mpesa_receipt"),
                    latestPayment.optString("transaction_date")
                ).filter(String::isNotBlank).joinToString(" • "),
                inferRecordTone(latestPayment, toneBlue)
            )
            if (latestPayment.optInt("id") > 0) {
                paymentCard.isClickable = true
                paymentCard.isFocusable = true
                paymentCard.setOnClickListener { showPayment(latestPayment.optInt("id")) }
            }
            form.addView(paymentCard)
        }
        form.addView(sectionPanel(
            title = metersSection?.optString("title").orEmpty().ifBlank { "Meters and service points" },
            description = "Track active meters and move quickly into readings or support workflows."
        ) {
            addRecordList(
                parent = this,
                heading = metersSection?.optString("title").orEmpty().ifBlank { "Meters" },
                rows = data.optJSONArray("meters"),
                titleFields = listOf("meter_number", "meter_label"),
                emptyState = metersSection?.optString("empty_state").orEmpty().ifBlank { "No active meters found." },
                showHeading = false
            )
        })
        form.addView(sectionPanel(
            title = "Quick actions",
            description = "Shortcuts styled like the web operations tiles so common tasks are easier to scan."
        ) {
            addView(quickActionGrid(listOf(
            Triple("Statement", R.drawable.ic_receipt, ::showStatement),
            Triple("Bills", R.drawable.ic_wallet) { showBills() },
            Triple("Payments", R.drawable.ic_circle_check, ::showPayments),
            Triple("Meters", R.drawable.ic_meter, ::showMeters),
            Triple("Readings", R.drawable.ic_circle_clock, ::showReadings),
            Triple("Submit reading", R.drawable.ic_meter, ::showSubmitReading),
            Triple("Complaints", R.drawable.ic_support, ::showComplaints),
            Triple("Support chat", R.drawable.ic_support, ::showSupportChat)
            )))
        })
        if (user?.optString("role", "customer")?.lowercase() != "customer") {
            form.addView(sectionTitle("Staff workspaces"))
            addWorkspace(form, "Customers & service", "Customer records, onboarding, locations and complaints", R.drawable.ic_group, ::showCustomersWorkspace)
            addWorkspace(form, "Billing & payments", "Collections, invoicing, corrections and payment operations", R.drawable.ic_receipt, ::showBillingWorkspace)
            addWorkspace(form, "Finance & accounting", "Approvals, reports, ledgers, budgets and transfers", R.drawable.ic_wallet, ::showFinanceWorkspace)
            addWorkspace(form, "Operations & support", "Demand notices, inquiries, integrations and publishing", R.drawable.ic_support, ::showOperationsWorkspace)
            addWorkspace(form, "System administration", "Staff access, permissions, settings and audit logs", R.drawable.ic_admin, ::showSystemWorkspace)
        }
        show(form)
    }

    private fun screenSection(screen: JSONObject, key: String): JSONObject? {
        val sections = screen.optJSONArray("sections") ?: return null
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            if (section.optString("key") == key) {
                return section
            }
        }
        return null
    }

    private fun mobileActionSpec(action: JSONObject?, contextUser: JSONObject? = null): Pair<String, () -> Unit>? {
        if (action == null) return null
        val label = action.optString("label").trim()
        if (label.isBlank()) return null
        val callback = when (action.optString("target").trim()) {
            "/api/mobile/registration_payment.php" -> ::loadRegistrationPayment
            "/api/mobile/bills.php" -> { { showBills() } }
            "/api/mobile/payments.php" -> ::showPayments
            "/api/mobile/me.php" -> ::showProfile
            "/api/mobile/profile_update.php" -> contextUser?.let { user -> { showEditProfile(user) } }
            "/api/mobile/statement.php" -> ::showStatement
            "/api/mobile/meters.php" -> ::showMeters
            "/api/mobile/dashboard.php" -> ::showDashboard
            "/api/mobile/change_password.php" -> { { showChangePassword() } }
            "/api/mobile/theme.php" -> ::showProfile
            else -> null
        } ?: return null
        return label to callback
    }

    private fun showCustomersWorkspace() = showWorkspace(
        "Customers & service",
        "Manage customer relationships and field service.",
        listOf(
            "Customers" to ::showCustomers,
            "Search customers" to ::showClientSearch,
            "Customer management" to ::showCustomerManagement,
            "Onboarding tracker" to ::showOnboarding,
            "Registration proformas" to ::showRegistrationProformas,
            "Customer locations" to ::showCustomerLocations,
            "Manage complaints" to ::showAdminComplaints
        )
    )

    private fun showBillingWorkspace() = showWorkspace(
        "Billing & payments",
        "Manage billing, collections and payment workflows.",
        listOf(
            "Collections" to { showCollections() },
            "Record payment" to ::showManualPayment,
            "Payments workspace" to ::showPaymentsWorkspace,
            "Payment transactions" to ::showPaymentTransactions,
            "Invoicing" to ::showInvoicing,
            "Bill correction" to ::showBillCorrection
        )
    )

    private fun showFinanceWorkspace() {
        backAction = ::showDashboard
        val form = screen("Finance & accounting", "A clear workspace for approvals, books and financial reports.")
        form.addView(sectionTitle("Finance operations"))
        addWorkspace(
            form,
            "Approvals",
            "Review payments and requests that need a finance decision.",
            R.drawable.ic_circle_check,
            ::showApprovals,
            toneAmber
        )
        addWorkspace(
            form,
            "Fund transfers",
            "Move funds between accounts and review recent transfers.",
            R.drawable.ic_wallet,
            ::showAccountingTransfers,
            toneTeal
        )
        form.addView(sectionTitle("Accounting"))
        addWorkspace(
            form,
            "Accounting overview",
            "Chart of accounts, trial balance and period controls.",
            R.drawable.ic_receipt,
            ::showAccountingOverview,
            toneBlue
        )
        addWorkspace(
            form,
            "General ledger",
            "Browse account movements and journal entry details.",
            R.drawable.ic_receipt,
            ::showAccountingLedger,
            toneBlue
        )
        addWorkspace(
            form,
            "Budgets",
            "Set yearly budgets and compare them with actuals.",
            R.drawable.ic_wallet,
            ::showAccountingBudget,
            toneAmber
        )
        form.addView(sectionTitle("Reports"))
        addWorkspace(
            form,
            "Accounting reports",
            "Balance sheet, profit and loss, cash flow and receivables.",
            R.drawable.ic_receipt,
            ::showAccountingReports,
            toneTeal
        )
        addWorkspace(
            form,
            "Business reports",
            "Operational and collection reporting.",
            R.drawable.ic_circle_clock,
            ::showReports,
            toneBlue
        )
        show(form)
    }

    private fun showOperationsWorkspace() = showWorkspace(
        "Operations & support",
        "Monitor service communications and integrations.",
        listOf(
            "Support chat" to ::showSupportChat,
            "Messaging center" to ::showMessagingCenter,
            "Demand notices" to ::showDemandNotices,
            "Support inquiries" to ::showSupportInquiries,
            "Integration health" to ::showIntegrationHealth,
            "Blog" to ::showBlog
        )
    )

    private fun showSystemWorkspace() = showWorkspace(
        "System administration",
        "Control staff access, configuration and audit records.",
        listOf(
            "Staff users" to ::showStaffUsers,
            "Role permissions" to ::showRolePermissions,
            "Settings" to ::showSettings,
            "Terms & conditions" to ::showTermsConditions,
            "Activity logs" to ::showActivityLogs,
            "System logs" to ::showSystemLogs
        )
    )

    private fun showWorkspace(
        title: String,
        subtitle: String,
        destinations: List<Pair<String, () -> Unit>>
    ) {
        backAction = ::showDashboard
        val form = screen(title, subtitle)
        form.addView(heroBanner(
            title = title,
            message = subtitle,
            eyebrow = "Workspace",
            tone = toneBlue,
            iconRes = R.drawable.ic_circle_check,
            compact = true
        ))
        form.addView(summaryCardGrid(listOf(
            summaryCard("Modules", destinations.size.toString(), R.drawable.ic_circle_check, toneBlue),
            summaryCard("Focus", if (destinations.size > 4) "Full workspace" else "Task set", R.drawable.ic_receipt, toneTeal)
        )))
        form.addView(sectionPanel(
            title = "Available tools",
            description = "This mobile workspace follows the same grouped operations model as the web application."
        ) {
        destinations.forEach { (label, action) ->
            addWorkspace(
                this,
                label,
                "Open $label.",
                iconForWorkspaceLabel(label),
                action,
                toneForWorkspaceLabel(label)
            )
        }
        })
        show(form)
    }

    private fun showProfile() {
        childScreen()
        showLoading("Loading profile")
        api.request("me.php") { meResult ->
            onResult(meResult, "Could not load profile") { meResponse ->
                val user = meResponse.data().optJSONObject("user") ?: JSONObject()
                currentUser = user
                // Prefer the dedicated theme.php endpoint for the authoritative
                // preference and the server's list of available options; fall
                // back to the theme_preference already present on theme.php
                // user payload (or the last-known local value) if that request
                // fails, e.g. on an older backend or a transient network error.
                api.request("theme.php") { themeResult ->
                    runOnUiThread {
                        val themeData = themeResult.getOrNull()?.data()
                        val serverPreference = themeData?.optString("theme_preference")
                            ?.takeIf { it in VALID_THEME_PREFERENCES }
                            ?: user.optString("theme_preference").takeIf { it in VALID_THEME_PREFERENCES }
                            ?: currentThemePreference()
                        val availablePreferences = themeData?.optJSONArray("available_preferences")?.let { arr ->
                            (0 until arr.length()).mapNotNull { index -> arr.optString(index).takeIf(String::isNotBlank) }
                        }?.filter { it in VALID_THEME_PREFERENCES }?.takeIf { it.isNotEmpty() }
                            ?: listOf("system", "light", "dark")
                        if (serverPreference != currentThemePreference()) {
                            applyThemePreference(serverPreference)
                        }
                        renderProfile(meResponse.data(), user, serverPreference, availablePreferences)
                    }
                }
            }
        }
    }

    private fun renderProfile(profileData: JSONObject, user: JSONObject, themePreference: String, availablePreferences: List<String>) {
        val screen = profileData.optJSONObject("screen") ?: JSONObject()
        val userSection = screenSection(screen, "user")
        val metersSection = screenSection(screen, "meters")
        val linkedMeters = user.optJSONArray("meters") ?: JSONArray()
        val form = screen(screen.optString("title").ifBlank { "Profile" }, user.optString("full_name"))
        form.addView(heroBanner(
            title = user.optString("full_name").ifBlank { "Profile" },
            message = listOf(user.optString("account_number"), user.optString("status"))
                .filter(String::isNotBlank)
                .joinToString(" • "),
            eyebrow = user.optString("role").replaceFirstChar(Char::uppercase).ifBlank { "Account" },
            tone = toneForStatus(user.optString("status"), toneBlue),
            iconRes = R.drawable.ic_person,
            compact = true
        ))
        form.addView(summaryCardGrid(listOf(
            summaryCard("Account", user.optString("account_number").ifBlank { "Not set" }, R.drawable.ic_receipt, toneBlue),
            summaryCard("Status", user.optString("status").ifBlank { "Unknown" }, R.drawable.ic_circle_check, toneForStatus(user.optString("status"), toneBlue)),
            summaryCard("Theme", themePreferenceLabel(themePreference), R.drawable.ic_circle_clock, toneTeal),
            summaryCard("Linked meters", linkedMeters.length().toString(), R.drawable.ic_meter, toneBlue)
        )))
        val alerts = screen.optJSONArray("alerts") ?: JSONArray()
        for (index in 0 until alerts.length()) {
            val alert = alerts.optJSONObject(index) ?: continue
            addScreenAlert(form, alert)
        }
        val profileActions = mutableListOf<Pair<String, () -> Unit>>()
        mobileActionSpec(screen.optJSONObject("primary_action"), user)?.let(profileActions::add)
        val secondaryActions = screen.optJSONArray("secondary_actions") ?: JSONArray()
        for (index in 0 until secondaryActions.length()) {
            val actionObject = secondaryActions.optJSONObject(index) ?: continue
            if (actionObject.optString("target") == "/api/mobile/theme.php") continue
            mobileActionSpec(actionObject, user)?.let(profileActions::add)
        }
        if (profileActions.isNotEmpty()) {
            form.addView(sectionPanel(
                title = "Profile actions",
                description = "Use the same top-level account actions exposed by the server."
            ) {
                val primary = profileActions.firstOrNull()
                primary?.let { (label, action) ->
                    addView(actionButton(label).apply {
                        setOnClickListener { action() }
                    })
                }
                val secondary = profileActions.drop(1)
                if (secondary.isNotEmpty()) {
                    addView(buttonRow(*secondary.toTypedArray()))
                }
            })
        }
        form.addView(sectionPanel(
            title = userSection?.optString("title").orEmpty().ifBlank { "Account details" },
            description = "Core identity and service information returned by the profile endpoint."
        ) {
            if (userSection != null && (userSection.optJSONArray("fields")?.length() ?: 0) > 0) {
                addProfileFields(this, userSection.optJSONArray("fields"), user, themePreference)
            } else {
                addField(this, "Full name", user.optString("full_name"))
                addField(this, "Account", user.optString("account_number"))
                addField(this, "Phone", user.optString("phone_number"))
                addField(this, "Email", user.optString("email"))
                addField(this, "Address", user.optString("address"))
                addField(this, "Connection type", user.optString("connection_type"))
                addField(this, "Theme preference", themePreferenceLabel(themePreference))
            }
        })
        form.addView(sectionPanel(
            title = "Appearance",
            description = "Choose how My Water Bill looks on this device. Current: ${themePreferenceLabel(themePreference)}."
        ) {
            addView(optionGroup(
                "Theme",
                availablePreferences.map { it to themePreferenceLabel(it) },
                themePreference,
                1
            ) { selected -> updateThemePreference(selected, user) })
        })
        form.addView(sectionPanel(
            title = metersSection?.optString("title").orEmpty().ifBlank { "Linked meters" },
            description = "Meters associated with this account and available for readings or service checks."
        ) {
            addRecordList(
                parent = this,
                heading = metersSection?.optString("title").orEmpty().ifBlank { "Linked meters" },
                rows = linkedMeters,
                titleFields = listOf("meter_number", "meter_label"),
                emptyState = metersSection?.optString("empty_state").orEmpty().ifBlank { "No linked meters found." },
                showHeading = false
            )
        })
        addBack(form)
        show(form)
    }

    // Posts the selected preference to the dedicated theme.php endpoint (never
    // profile_update.php, which only accepts it as a fallback server-side).
    // The local preference/applied night mode are only updated after a
    // confirmed success, so a server error never leaves the device looking
    // like it changed appearance when the account did not.
    private fun updateThemePreference(preference: String, user: JSONObject) {
        if (preference == currentThemePreference()) return
        api.request(
            "theme.php",
            "POST",
            JSONObject().put("theme_preference", preference)
        ) { result ->
            runOnUiThread {
                result.onSuccess {
                    pendingReturnToProfile = true
                    applyThemePreference(preference)
                    user.put("theme_preference", preference)
                    toast(it.optString("message", "Appearance set to ${themePreferenceLabel(preference)}."))
                    showProfile()
                }.onFailure { error ->
                    pendingReturnToProfile = false
                    handleError(error)
                }
            }
        }
    }

    private fun showEditProfile(user: JSONObject) {
            backAction = ::showProfile
            val name = input("Full name").apply { setText(user.optString("full_name")) }
            val phone = input("Phone", InputType.TYPE_CLASS_PHONE).apply {
                setText(user.optString("phone_number"))
            }
            val email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply {
                setText(user.optString("email"))
            }
            val address = input("Address").apply { setText(user.optString("address")) }
            val taxPin = input("Tax PIN (optional)")
            val twoFactorMethod = input("2FA method: sms or email").apply { setText(R.string.two_factor_method_default) }
            val twoFactor = CheckBox(this).apply {
                text = getString(R.string.enable_two_factor)
                isChecked = user.optBoolean("two_factor_enabled")
            }
            val save = actionButton("Save profile")
            val form = screen("Edit profile", "Update your contact and security settings.")
            listOf(name, phone, email, address, taxPin, twoFactor, twoFactorMethod, save).forEach(form::addView)
            addBack(form)
            save.setOnClickListener {
                setLoading(save, true, "Save profile")
                api.request(
                    "profile_update.php",
                    "POST",
                    JSONObject()
                        .put("full_name", name.text.toString().trim())
                        .put("phone_number", phone.text.toString().trim())
                        .put("email", email.text.toString().trim())
                        .put("address", address.text.toString().trim())
                        .put("tax_pin", taxPin.text.toString().trim())
                        .put("two_factor_enabled", twoFactor.isChecked)
                        .put("two_factor_method", twoFactorMethod.text.toString().trim().lowercase())
                ) { result ->
                    runOnUiThread {
                        setLoading(save, false, "Save profile")
                        result.onSuccess {
                            currentUser = it.data().optJSONObject("user")
                            toast(it.optString("message", "Profile updated."))
                            showProfile()
                        }.onFailure(::handleError)
                    }
                }
            }
            show(form)
        }

    private fun showChangePassword(forced: Boolean = false) {
            backAction = if (forced) null else ::showProfile
            val current = input(
                "Current password",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            val next = input(
                "New password",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            val confirm = input(
                "Confirm new password",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            val save = actionButton("Change password")
            val form = screen(
                "Change password",
                if (forced) "You must choose a new password before continuing." else "Use at least 6 characters."
            )
            listOf(current, next, confirm, save).forEach(form::addView)
            if (!forced) addBack(form)
            save.setOnClickListener {
                if (next.text.toString() != confirm.text.toString()) {
                    toast("New passwords do not match.")
                    return@setOnClickListener
                }
                setLoading(save, true, "Change password")
                api.request(
                    "change_password.php",
                    "POST",
                    JSONObject()
                        .put("current_password", current.text.toString())
                        .put("new_password", next.text.toString())
                        .put("confirm_password", confirm.text.toString())
                ) { result ->
                    runOnUiThread {
                        setLoading(save, false, "Change password")
                        result.onSuccess {
                            toast(it.optString("message", "Password changed."))
                            if (forced) restoreSession() else showProfile()
                        }.onFailure(::handleError)
                    }
                }
            }
            show(form)
        }

    private fun showStatement() {
            childScreen()
            showLoading("Loading statement")
            api.request("statement.php") { result ->
                onResult(result, "Could not load statement") { response ->
                    val data = response.data()
                    val summary = data.optJSONObject("summary") ?: JSONObject()
                    val bills = data.optJSONArray("bills") ?: JSONArray()
                    val payments = data.optJSONArray("payments") ?: JSONArray()
                    val form = screen("Account statement", "Bills and completed payments")
                    form.addView(heroBanner(
                        title = "Statement overview",
                        message = "Review billed, paid and outstanding amounts in one place.",
                        eyebrow = "Account activity",
                        tone = if (summary.optDouble("outstanding_amount") > 0.01) toneAmber else toneTeal,
                        iconRes = R.drawable.ic_receipt,
                        compact = true
                    ))
                    form.addView(summaryCardGrid(listOf(
                        summaryCard("Billed", money(summary.optDouble("billed_amount")), R.drawable.ic_receipt, toneBlue),
                        summaryCard("Paid", money(summary.optDouble("paid_amount")), R.drawable.ic_circle_check, toneTeal),
                        summaryCard(
                            "Outstanding",
                            money(summary.optDouble("outstanding_amount")),
                            R.drawable.ic_wallet,
                            if (summary.optDouble("outstanding_amount") > 0.01) toneAmber else toneTeal
                        )
                    )))
                    form.addView(sectionPanel(
                        title = "Billing history",
                        description = "A statement-style list of bills, closer to the web statement and billing history views."
                    ) {
                        addRecordList(this, "Bills", bills, listOf("billing_month", "type_label")) { bill ->
                            showBill(bill.optInt("id"))
                        }
                    })
                    form.addView(sectionPanel(
                        title = "Completed payments",
                        description = "Settlements applied to your account during the statement period."
                    ) {
                        addPaymentRows(this, payments, true)
                    })
                    addBack(form)
                    show(form)
                }
            }
        }

    private fun showComplaints() {
            childScreen()
            val subject = input("Complaint subject")
            val message = multilineInput("Describe the issue")
            val submit = actionButton("Submit complaint")
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val form = screen("Support complaints", "Submit and track service issues.")
            form.addView(heroBanner(
                title = "Service complaints",
                message = "Raise an issue and follow its progress without leaving the app.",
                eyebrow = "Customer care",
                tone = toneAmber,
                iconRes = R.drawable.ic_support,
                compact = true
            ))
            form.addView(sectionPanel(
                title = "New complaint",
                description = "Matches the web complaints page flow: submit first, then track status history below."
            ) {
                addView(subject)
                addView(message)
                addView(submit)
            })
            form.addView(sectionPanel(
                title = "Complaint history",
                description = "Status and submission history for all complaints tied to this account."
            ) {
                addView(list)
            })
            addBack(form)
            fun load() {
                api.request("complaints.php") { result ->
                    runOnUiThread {
                        result.onSuccess { response ->
                            list.removeAllViews()
                            val rows = response.data().optJSONArray("complaints") ?: JSONArray()
                            val openCount = (0 until rows.length()).count { index ->
                                rows.optJSONObject(index)?.optString("status")?.lowercase() !in setOf("resolved", "closed", "handled")
                            }
                            if (form.findViewWithTag<View>("complaint-summary") == null) {
                                form.addView(summaryCardGrid(listOf(
                                    summaryCard("Complaints", rows.length().toString(), R.drawable.ic_support, toneBlue),
                                    summaryCard("Open", openCount.toString(), R.drawable.ic_warning_triangle, if (openCount > 0) toneAmber else toneTeal)
                                )).apply { tag = "complaint-summary" }, 1)
                            }
                            if (rows.length() == 0) list.addView(empty("No complaints submitted."))
                            for (index in 0 until rows.length()) {
                                val row = rows.optJSONObject(index) ?: continue
                                list.addView(card(
                                    row.optString("subject"),
                                    "${row.optString("status")} • ${row.optString("created_at")}\n${row.optString("message")}",
                                    toneForStatus(row.optString("status"), toneBlue)
                                ))
                            }
                        }.onFailure(::handleError)
                    }
                }
            }
            submit.setOnClickListener {
                setLoading(submit, true, "Submit complaint")
                api.request(
                    "complaints.php",
                    "POST",
                    JSONObject()
                        .put("subject", subject.text.toString().trim())
                        .put("message", message.text.toString().trim())
                ) { result ->
                    runOnUiThread {
                        setLoading(submit, false, "Submit complaint")
                        result.onSuccess {
                            subject.text.clear()
                            message.text.clear()
                            toast(it.optString("message", "Complaint submitted."))
                            load()
                        }.onFailure(::handleError)
                    }
                }
            }
            show(form)
            load()
    }

    private fun showBills(status: String = "", limit: Int = 100, page: Int = 1) {
        childScreen()
        showLoading("Loading bills")
        val path = api.query("bills.php", mapOf("limit" to limit.toString(), "status" to status, "page" to page.toString()))
        api.request(path) { result ->
            onResult(result, "Could not load bills") { response ->
                val data = response.data()
                val bills = data.optJSONArray("bills") ?: JSONArray()
                val screen = data.optJSONObject("screen") ?: JSONObject()
                val billSection = screenSection(screen, "bills")
                val filters = screen.optJSONArray("filters") ?: JSONArray()
                val statusFilter = filters.optJSONObject(0)
                val limitFilter = filters.optJSONObject(1)
                val statusOptions = statusFilter?.optJSONArray("options")?.toFlexibleOptionPairs()
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf("" to "All Bills", "pending" to "Pending", "paid" to "Paid", "overdue" to "Overdue", "cancelled" to "Cancelled")
                val limitOptions = limitFilter?.optJSONArray("options")?.toFlexibleOptionPairs()
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf("10" to "10", "20" to "20", "50" to "50", "100" to "100")
                val statusField = dropdownInput(statusFilter?.optString("label").orEmpty().ifBlank { "Bill status" }, statusOptions, status)
                val limitField = dropdownInput(limitFilter?.optString("label").orEmpty().ifBlank { "Rows" }, limitOptions, limit.toString())
                val pagination = screen.optJSONObject("pagination") ?: JSONObject()
                val totalBills = pagination.optInt("total", data.optInt("total", bills.length()))
                val form = screen(screen.optString("title").ifBlank { "Bills" }, "$totalBills bill(s)")
                val outstandingTotal = (0 until bills.length()).sumOf { index ->
                    bills.optJSONObject(index)?.optDouble("outstanding_amount") ?: 0.0
                }
                val overdueCount = (0 until bills.length()).count { index ->
                    bills.optJSONObject(index)?.optString("status")?.equals("overdue", ignoreCase = true) == true
                }
                form.addView(heroBanner(
                    title = "Billing history",
                    message = "Browse current charges and open individual bills using the same grouped flow as the web bills page.",
                    eyebrow = "My account",
                    tone = if (outstandingTotal > 0.01) toneAmber else toneTeal,
                    iconRes = R.drawable.ic_receipt,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Bills in view", bills.length().toString(), R.drawable.ic_receipt, toneBlue),
                    summaryCard("Outstanding", money(outstandingTotal), R.drawable.ic_wallet, if (outstandingTotal > 0.01) toneAmber else toneTeal),
                    summaryCard("Overdue", overdueCount.toString(), R.drawable.ic_warning_triangle, if (overdueCount > 0) toneRed else toneTeal)
                )))
                form.addView(sectionPanel(
                    title = "Filters and exports",
                    description = "Use status and row controls to focus the bill list, similar to the web billing tools."
                ) {
                    addView(statusField)
                    addView(limitField)
                    addView(buttonRow(
                        "Apply filters" to {
                            childScreen()
                            showBills(statusField.tag?.toString().orEmpty(), limitField.tag?.toString()?.toIntOrNull() ?: limit, 1)
                        },
                        "Clear" to {
                            childScreen()
                            showBills("", limitField.tag?.toString()?.toIntOrNull() ?: 100, 1)
                        }
                    ))
                })
                form.addView(sectionTitle(billSection?.optString("title").orEmpty().ifBlank { "Bills in view" }))
                if (bills.length() == 0) form.addView(empty(billSection?.optString("empty_state").orEmpty().ifBlank { "No bills found." }))
                val itemActions = billSection?.optJSONArray("item_actions") ?: JSONArray()
                for (index in 0 until bills.length()) {
                    val bill = bills.optJSONObject(index) ?: continue
                    val view = card(
                        "${bill.optString("type_label", "Bill")} • ${bill.optString("billing_month")}",
                        listOfNotNull(
                            "Outstanding ${money(bill.optDouble("outstanding_amount"))}",
                            bill.optString("status"),
                            bill.optString("due_date").takeIf(String::isNotBlank)?.let { "Due $it" }
                        ).joinToString(" • "),
                        toneForStatus(bill.optString("status"), toneBlue)
                    )
                    view.isClickable = true
                    view.isFocusable = true
                    view.setOnClickListener { showBill(bill.optInt("id")) }
                    form.addView(view)
                    for (actionIndex in 0 until itemActions.length()) {
                        val action = itemActions.optJSONObject(actionIndex) ?: continue
                        if (action.optString("type") == "link") {
                            val field = action.optString("field")
                            val label = action.optString("label").ifBlank { "Open link" }
                            if (field.contains("payment", ignoreCase = true) && !field.contains("receipt", ignoreCase = true)) {
                                if (bill.optDouble("outstanding_amount") > 0.01) {
                                    form.addView(secondaryButton("Pay with M-Pesa").apply {
                                        setOnClickListener { showPaymentForm(bill.optInt("id")) }
                                    })
                                }
                            } else {
                                addUrlButton(form, label, bill.optString(field))
                            }
                        }
                    }
                }
                if (pagination.optBoolean("has_previous_page") || pagination.optBoolean("has_next_page")) {
                    val pageValue = pagination.optInt("page", page)
                    val limitValue = pagination.optInt("limit", limit)
                    val buttons = mutableListOf<Pair<String, () -> Unit>>()
                    if (pagination.optBoolean("has_previous_page")) {
                        buttons += "Previous" to { childScreen(); showBills(statusField.tag?.toString().orEmpty(), limitValue, pageValue - 1) }
                    }
                    if (pagination.optBoolean("has_next_page")) {
                        buttons += "Next" to { childScreen(); showBills(statusField.tag?.toString().orEmpty(), limitValue, pageValue + 1) }
                    }
                    if (buttons.isNotEmpty()) {
                        form.addView(buttonRow(*buttons.toTypedArray()))
                    }
                }
                mobileActionSpec(screen.optJSONObject("primary_action"))?.let { (label, action) ->
                    val button = secondaryButton(label)
                    button.setOnClickListener { action() }
                    form.addView(button)
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showBill(id: Int) {
        backAction = ::showBills
        showLoading("Loading bill")
        api.request(api.query("bill.php", mapOf("id" to id.toString()))) { result ->
            onResult(result, "Could not load bill") { response ->
                val bill = response.data().optJSONObject("bill") ?: JSONObject()
                val form = screen(bill.optString("type_label", "Bill"), bill.optString("billing_month"))
                form.addView(heroBanner(
                    title = bill.optString("type_label", "Bill"),
                    message = listOf(bill.optString("billing_month"), bill.optString("account_number"))
                        .filter(String::isNotBlank)
                        .joinToString(" • "),
                    eyebrow = "Invoice detail",
                    tone = toneForStatus(bill.optString("status"), toneBlue),
                    iconRes = R.drawable.ic_receipt,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Total", money(bill.optDouble("amount")), R.drawable.ic_receipt, toneBlue),
                    summaryCard("Paid", money(bill.optDouble("paid_amount")), R.drawable.ic_circle_check, toneTeal),
                    summaryCard("Outstanding", money(bill.optDouble("outstanding_amount")), R.drawable.ic_wallet, if (bill.optDouble("outstanding_amount") > 0.01) toneAmber else toneTeal),
                    summaryCard("Status", bill.optString("status").ifBlank { "Unknown" }, R.drawable.ic_warning_triangle, toneForStatus(bill.optString("status"), toneBlue))
                )))
                form.addView(sectionPanel(
                    title = "Bill overview",
                    description = "Meter readings, billing totals and due date for this charge."
                ) {
                    addField(this, "Account", bill.optString("account_number"))
                    addField(this, "Previous reading", bill.optString("previous_reading"))
                    addField(this, "Current reading", bill.optString("current_reading"))
                    addField(this, "Consumption", bill.optString("consumption"))
                    addField(this, "Due date", bill.optString("due_date"))
                })
                form.addView(sectionPanel(
                    title = "Bill actions",
                    description = "Pay online, open the invoice or request financial assistance from one block."
                ) {
                    if (bill.optDouble("outstanding_amount") > 0.01) {
                        val pay = actionButton("Pay with M-Pesa")
                        pay.setOnClickListener { showPaymentForm(id) }
                        addView(pay)
                    }
                    addDocumentButton(this, "Open invoice", bill.optString("document_url"))
                    if (bill.optDouble("outstanding_amount") > 0.01) {
                        val request = secondaryButton("Request installment, waiver or write-off")
                        request.setOnClickListener { showBillAction(id, bill.optDouble("outstanding_amount")) }
                        addView(request)
                    }
                })
                form.addView(sectionPanel(
                    title = "Line items",
                    description = "Charge components for this bill, similar to the invoice breakdown on the web app."
                ) {
                    addJsonRows(this, "Line items", bill.optJSONArray("line_items"), listOf("description", "line_type", "amount"))
                })
                form.addView(sectionPanel(
                    title = "Payments against this bill",
                    description = "Settlements already applied to this invoice."
                ) {
                    addPaymentRows(this, bill.optJSONArray("payments"))
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showPaymentForm(billId: Int) {
        val returnToBill = { showBill(billId) }
        backAction = returnToBill
        val phone = input("M-Pesa phone number (optional)", InputType.TYPE_CLASS_PHONE)
        val submit = actionButton("Send M-Pesa prompt")
        val form = screen("Pay bill", "You will receive an STK prompt on your phone.")
        form.addView(phone)
        form.addView(submit)
        addBack(form)
        submit.setOnClickListener {
            val body = JSONObject().put("bill_id", billId)
            phone.text.toString().trim().takeIf(String::isNotBlank)?.let { body.put("phone", it) }
            setLoading(submit, true, "Send M-Pesa prompt")
            api.request("initiate_payment.php", "POST", body) { result ->
                runOnUiThread {
                    setLoading(submit, false, "Send M-Pesa prompt")
                    result.onSuccess { response ->
                        toast(response.optString("message", "Payment initiated."))
                        val payment = response.data().optJSONObject("payment")
                        val paymentId = payment?.optInt("payment_id")?.takeIf { it > 0 }
                        val checkoutRequestId = payment?.optString("checkout_request_id")?.takeIf(String::isNotBlank)
                        showPaymentStatus(billId, paymentId, checkoutRequestId)
                    }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Payment status polling. Started right after an STK push is initiated;
    // polls payment_status.php roughly every 5 seconds (server can override
    // via polling.recommended_interval_seconds) until polling.should_continue
    // is false. Polling is safely cancelled whenever the user navigates away,
    // because every screen render bumps screenEpoch and each scheduled poll
    // checks it still matches before doing any work.
    // ---------------------------------------------------------------------

    private fun showPaymentStatus(billId: Int, paymentId: Int?, checkoutRequestId: String?) {
        backAction = { showBill(billId) }
        val form = screen("Payment status", "Checking your M-Pesa payment...")
        form.addView(statusBanner("Sending request to M-Pesa", "Please wait a moment.", toneBlue, R.drawable.ic_circle_clock, true))
        addBack(form)
        show(form)
        fetchPaymentStatus(screenEpoch, billId, paymentId, checkoutRequestId, isManualRefresh = false)
    }

    private fun fetchPaymentStatus(
        epoch: Long,
        billId: Int,
        paymentId: Int?,
        checkoutRequestId: String?,
        isManualRefresh: Boolean
    ) {
        val params = linkedMapOf<String, String>()
        when {
            paymentId != null && paymentId > 0 -> params["payment_id"] = paymentId.toString()
            !checkoutRequestId.isNullOrBlank() -> params["checkout_request_id"] = checkoutRequestId
            else -> params["bill_id"] = billId.toString()
        }
        api.request(api.query("payment_status.php", params)) { result ->
            runOnUiThread {
                if (screenEpoch != epoch) return@runOnUiThread
                result.onSuccess { response ->
                    val data = response.data()
                    val payment = data.optJSONObject("payment") ?: JSONObject()
                    val polling = data.optJSONObject("polling") ?: JSONObject()
                    renderPaymentStatus(billId, payment)
                    val newEpoch = screenEpoch
                    if (polling.optBoolean("should_continue", false)) {
                        val seconds = polling.optInt("recommended_interval_seconds", 5).let { if (it in 1..120) it else 5 }
                        val nextPaymentId = payment.optInt("id").takeIf { it > 0 } ?: paymentId
                        val nextCheckoutId = payment.optString("checkout_request_id").takeIf(String::isNotBlank) ?: checkoutRequestId
                        mainHandler.postDelayed({
                            fetchPaymentStatus(newEpoch, billId, nextPaymentId, nextCheckoutId, false)
                        }, seconds * 1000L)
                    }
                }.onFailure { error ->
                    if (isManualRefresh) {
                        handleError(error)
                    } else {
                        renderPaymentStatusError(billId, paymentId, checkoutRequestId, error)
                    }
                }
            }
        }
    }

    private fun renderPaymentStatus(billId: Int, payment: JSONObject) {
        val isFinal = payment.optBoolean("is_final", false)
        val isSuccessful = payment.optBoolean("is_successful", false)
        val statusText = payment.optString("status", "pending")
        val form = screen("Payment status", "Bill ${payment.optString("billing_month")}".trim())
        when {
            isFinal && isSuccessful -> form.addView(statusBanner(
                "Payment successful",
                "Confirmed via M-Pesa" + payment.optString("mpesa_receipt").takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty(),
                toneTeal, R.drawable.ic_circle_check, false
            ))
            isFinal && !isSuccessful -> form.addView(statusBanner(
                "Payment ${statusText.ifBlank { "failed" }}",
                "The M-Pesa request was not completed. You can try again.",
                toneRed, R.drawable.ic_circle_alert, false
            ))
            else -> form.addView(statusBanner(
                "Waiting for M-Pesa confirmation",
                "Enter your M-Pesa PIN on your phone to complete this payment.",
                toneAmber, R.drawable.ic_circle_clock, true
            ))
        }
        form.addView(summaryCardGrid(listOf(
            summaryCard("Amount", money(payment.optDouble("amount")), R.drawable.ic_wallet, toneBlue),
            summaryCard("Outstanding", money(payment.optDouble("outstanding_amount")), R.drawable.ic_receipt, if (payment.optDouble("outstanding_amount") > 0.01) toneAmber else toneTeal),
            summaryCard("Receipt", payment.optString("mpesa_receipt").ifBlank { "Pending" }, R.drawable.ic_circle_check, toneTeal),
            summaryCard("Status", statusText.replaceFirstChar(Char::uppercase), R.drawable.ic_warning_triangle, toneForStatus(statusText, toneBlue))
        )))
        form.addView(sectionPanel(
            title = "Payment details",
            description = "Tracks the same fields surfaced in the web receipt and payment status views."
        ) {
            addField(this, "Phone", payment.optString("phone_number"))
            addField(this, "Transaction date", payment.optString("transaction_date").ifBlank { "—" })
            addField(this, "Checkout request", payment.optString("checkout_request_id").ifBlank { "—" })
        })
        form.addView(sectionPanel(
            title = "Actions",
            description = "Refresh the live payment state or open the final receipt when available."
        ) {
            if (isFinal && isSuccessful) {
                addDocumentButton(this, "View receipt PDF", payment.optString("receipt_pdf_url"))
            }
            val refresh = secondaryButton("Refresh status")
            refresh.setOnClickListener {
                setLoading(refresh, true, "Refresh status")
                fetchPaymentStatus(
                    screenEpoch, billId,
                    payment.optInt("id").takeIf { it > 0 },
                    payment.optString("checkout_request_id").takeIf(String::isNotBlank),
                    isManualRefresh = true
                )
            }
            addView(refresh)
        })
        when {
            isFinal && isSuccessful -> {
                val viewBill = actionButton("View bill")
                viewBill.setOnClickListener { showBill(billId) }
                form.addView(viewBill)
                val toDashboard = secondaryButton("Go to dashboard")
                toDashboard.setOnClickListener { showDashboard() }
                form.addView(toDashboard)
            }
            isFinal && !isSuccessful -> {
                val tryAgain = actionButton("Try again")
                tryAgain.setOnClickListener { showPaymentForm(billId) }
                form.addView(tryAgain)
                addBack(form)
            }
            else -> {
                form.addView(body("This screen updates automatically every few seconds. You can leave safely — the payment keeps processing on M-Pesa and you can check back from the bill at any time.").apply {
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                })
                addBack(form)
            }
        }
        show(form)
    }

    private fun renderPaymentStatusError(billId: Int, paymentId: Int?, checkoutRequestId: String?, error: Throwable) {
        val form = screen("Payment status", "We could not check the latest status.")
        form.addView(statusBanner(
            "Could not check status",
            error.message ?: "A network error occurred while checking your payment.",
            toneRed, R.drawable.ic_circle_alert, false
        ))
        form.addView(sectionPanel(
            title = "What you can do",
            description = "Retry the status check or return to the bill while the payment continues to process on M-Pesa."
        ) {
            val retry = actionButton("Try again")
            retry.setOnClickListener {
                setLoading(retry, true, "Try again")
                fetchPaymentStatus(screenEpoch, billId, paymentId, checkoutRequestId, isManualRefresh = true)
            }
            addView(retry)
        })
        addBack(form)
        show(form)
    }

    private fun statusBanner(title: String, message: String, tone: Tone, iconRes: Int, showProgress: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToOutline = true
        background = roundedDrawable(tone.tint, tone.border, 16f)
        elevation = dp(1).toFloat()
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView(iconRes, tone.onTint, 26).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(tone.onTint)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (showProgress) {
                addView(ProgressBar(this@MainActivity).apply {
                    indeterminateTintList = ColorStateList.valueOf(tone.onTint)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                })
            }
        })
        if (message.isNotBlank()) {
            addView(body(message).apply {
                setTextColor(tone.onTint)
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun showBillAction(billId: Int, outstanding: Double) {
        backAction = { showBill(billId) }
        val action = input("Action: installment, waiver or writeoff")
        val amount = input(
            "Amount (maximum ${money(outstanding)})",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val installments = input("Installment count (default 3)", InputType.TYPE_CLASS_NUMBER)
        val frequency = input("Frequency: weekly or monthly").apply { setText(R.string.payment_frequency_monthly) }
        val startDate = input("Start date YYYY-MM-DD (optional)")
        val reason = multilineInput("Reason")
        val submit = actionButton("Submit request")
        val form = screen("Bill request", "Requests are sent for financial approval.")
        listOf(action, amount, installments, frequency, startDate, reason, submit).forEach(form::addView)
        addBack(form)
        submit.setOnClickListener {
            val actionValue = action.text.toString().trim().lowercase()
            if (actionValue !in setOf("installment", "waiver", "writeoff")) {
                toast("Action must be installment, waiver or writeoff.")
                return@setOnClickListener
            }
            val body = JSONObject()
                .put("bill_id", billId)
                .put("action", actionValue)
                .put("amount", amount.text.toString().toDoubleOrNull() ?: 0.0)
                .put("reason", reason.text.toString().trim())
            if (actionValue == "installment") {
                body.put("installment_count", installments.text.toString().toIntOrNull() ?: 3)
                body.put("frequency", frequency.text.toString().trim().lowercase())
                startDate.text.toString().trim().takeIf(String::isNotBlank)?.let {
                    body.put("start_date", it)
                }
            }
            setLoading(submit, true, "Submit request")
            api.request("request_bill_action.php", "POST", body) { result ->
                runOnUiThread {
                    setLoading(submit, false, "Submit request")
                    result.onSuccess {
                        toast(it.optString("message", "Request submitted."))
                        showBill(billId)
                    }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showPayments() {
        childScreen()
        showLoading("Loading payments")
        api.request("payments.php?limit=100") { result ->
            onResult(result, "Could not load payments") { response ->
                val data = response.data()
                val payments = data.optJSONArray("payments") ?: JSONArray()
                val form = screen("Payments", "${data.optInt("total", payments.length())} payment(s)")
                val completedAmount = (0 until payments.length()).sumOf { index ->
                    payments.optJSONObject(index)?.optDouble("amount") ?: 0.0
                }
                val completedCount = (0 until payments.length()).count { index ->
                    payments.optJSONObject(index)?.optString("status")?.equals("completed", ignoreCase = true) == true
                }
                form.addView(heroBanner(
                    title = "Payment history",
                    message = "Track settlement status and open individual receipts.",
                    eyebrow = "Collections",
                    tone = toneTeal,
                    iconRes = R.drawable.ic_wallet,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Payments", payments.length().toString(), R.drawable.ic_receipt, toneBlue),
                    summaryCard("Completed", completedCount.toString(), R.drawable.ic_circle_check, toneTeal),
                    summaryCard("Amount", money(completedAmount), R.drawable.ic_wallet, toneBlue)
                )))
                form.addView(sectionPanel(
                    title = "Payment activity",
                    description = "Recent payment records and receipt access in the same grouped style as the web payment views."
                ) {
                    addPaymentRows(this, payments, true)
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showPayment(id: Int) {
        backAction = ::showPayments
        showLoading("Loading payment")
        api.request(api.query("payment.php", mapOf("id" to id.toString()))) { result ->
            onResult(result, "Could not load payment") { response ->
                val payment = response.data().optJSONObject("payment") ?: JSONObject()
                val form = screen("Payment receipt", payment.optString("mpesa_receipt", "Payment"))
                form.addView(heroBanner(
                    title = "Payment receipt",
                    message = if (payment.optString("status").equals("completed", ignoreCase = true)) {
                        "Payment received and recorded on the account."
                    } else {
                        "Review the latest transaction state and receipt details."
                    },
                    eyebrow = "Receipt",
                    tone = toneForStatus(payment.optString("status"), toneBlue),
                    iconRes = R.drawable.ic_wallet,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Amount", money(payment.optDouble("amount")), R.drawable.ic_wallet, toneBlue),
                    summaryCard("Status", payment.optString("status").ifBlank { "Unknown" }, R.drawable.ic_circle_check, toneForStatus(payment.optString("status"), toneBlue)),
                    summaryCard("Method", payment.optString("payment_method").replaceFirstChar(Char::uppercase).ifBlank { "M-Pesa" }, R.drawable.ic_receipt, toneTeal)
                )))
                form.addView(sectionPanel(
                    title = "Receipt details",
                    description = "Structured like the web receipt page with the key transaction and account fields grouped together."
                ) {
                    addField(this, "Phone", payment.optString("phone_number"))
                    addField(this, "Account", payment.optString("account_number"))
                    addField(this, "M-Pesa receipt", payment.optString("mpesa_receipt"))
                    addField(this, "Transaction date", payment.optString("transaction_date"))
                })
                form.addView(sectionPanel(
                    title = "Next steps",
                    description = "Open the hosted receipt or PDF copy when available."
                ) {
                    addDocumentButton(this, "View receipt PDF", payment.optString("receipt_pdf_url"))
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showMeters() {
        childScreen()
        showLoading("Loading meters")
        api.request("meters.php") { result ->
            onResult(result, "Could not load meters") { response ->
                val meters = response.data().optJSONArray("meters") ?: JSONArray()
                val form = screen("Meters", "${meters.length()} linked meter(s)")
                val primaryCount = (0 until meters.length()).count { index -> meters.optJSONObject(index)?.optBoolean("is_primary") == true }
                form.addView(heroBanner(
                    title = "Meter inventory",
                    message = "Review linked meters and their service state from a cleaner utility view.",
                    eyebrow = "Service points",
                    tone = toneTeal,
                    iconRes = R.drawable.ic_meter,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Linked meters", meters.length().toString(), R.drawable.ic_meter, toneBlue),
                    summaryCard("Primary", primaryCount.toString(), R.drawable.ic_circle_check, toneTeal)
                )))
                form.addView(sectionPanel(
                    title = "Registered meters",
                    description = "Each service point attached to your account, similar to the linked meter tables on the web app."
                ) {
                    if (meters.length() == 0) addView(empty("No meters are linked to this account."))
                    for (index in 0 until meters.length()) {
                        val meter = meters.optJSONObject(index) ?: continue
                        addView(card(
                            meter.optString("meter_number"),
                            listOf(
                                meter.optString("meter_label"),
                                meter.optString("status"),
                                if (meter.optBoolean("is_primary")) "Primary meter" else ""
                            ).filter(String::isNotBlank).joinToString(" • "),
                            toneForStatus(meter.optString("status"), toneBlue)
                        ))
                    }
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showReadings() {
        childScreen()
        showLoading("Loading readings")
        api.request("meter_readings.php?limit=100") { result ->
            onResult(result, "Could not load meter readings") { response ->
                val readings = response.data().optJSONArray("meter_readings") ?: JSONArray()
                val form = screen("Meter readings", "${readings.length()} recent submission(s)")
                val pendingCount = (0 until readings.length()).count { index ->
                    readings.optJSONObject(index)?.optString("status")?.equals("pending", ignoreCase = true) == true
                }
                form.addView(heroBanner(
                    title = "Reading history",
                    message = "Track submitted readings, approval status and meter photo evidence.",
                    eyebrow = "Metering",
                    tone = if (pendingCount > 0) toneAmber else toneTeal,
                    iconRes = R.drawable.ic_circle_clock,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Submissions", readings.length().toString(), R.drawable.ic_meter, toneBlue),
                    summaryCard("Pending", pendingCount.toString(), R.drawable.ic_warning_triangle, if (pendingCount > 0) toneAmber else toneTeal)
                )))
                form.addView(sectionPanel(
                    title = "Submitted readings",
                    description = "Recent readings and their approval state, like the web reading submission history."
                ) {
                    if (readings.length() == 0) addView(empty("No meter readings submitted."))
                    for (index in 0 until readings.length()) {
                        val reading = readings.optJSONObject(index) ?: continue
                        addView(card(
                            "${reading.optString("meter_number")} • ${reading.optString("current_reading")}",
                            "${reading.optString("billing_month")} • ${reading.optString("status")}",
                            toneForStatus(reading.optString("status"), toneBlue)
                        ))
                        addDocumentButton(this, "View meter photo", reading.optString("photo_url"))
                    }
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showSubmitReading() {
        childScreen()
        selectedPhoto = null
        val reading = input("Current reading", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val meter = input("Meter number (optional)")
        val billingMonth = datePickerInput("Billing month", today(), "month")
        val dueDate = datePickerInput("Due date", today())
        readingInputs = listOf(reading, meter, billingMonth, dueDate)
        val choosePhoto = secondaryButton("Choose meter photo")
        readingPhotoLabel = body("No photo selected")
        val submit = actionButton("Submit reading")
        val form = screen("Submit meter reading", "A clear JPG or PNG meter photo is required.")
        form.addView(heroBanner(
            title = "Submit meter reading",
            message = "Capture a reading and attach photo proof so the submission can move through approval quickly.",
            eyebrow = "Metering",
            tone = toneBlue,
            iconRes = R.drawable.ic_meter,
            compact = true
        ))
        form.addView(sectionPanel(
            title = "Reading details",
            description = "Use the same core information as the web submit-reading form, with easier date selection on mobile."
        ) {
            readingInputs.forEach(this::addView)
            addView(choosePhoto)
            addView(readingPhotoLabel)
            addView(submit)
        })
        form.addView(statusBanner(
            "Photo required",
            "Upload a clear JPG or PNG image of the meter display. This helps billing staff review the submission faster.",
            toneAmber,
            R.drawable.ic_warning_triangle,
            false
        ))
        addBack(form)
        choosePhoto.setOnClickListener {
            photoPicker.launch(arrayOf("image/*"))
        }
        submit.setOnClickListener {
            val photo = selectedPhoto
            if (reading.text.isBlank() || photo == null) {
                toast("Enter the reading and choose a meter photo.")
                return@setOnClickListener
            }
            val fields = mutableMapOf("current_reading" to reading.text.toString())
            if (meter.text.isNotBlank()) fields["meter_number"] = meter.text.toString().trim()
            if (billingMonth.text.isNotBlank()) fields["billing_month"] = billingMonth.text.toString().trim()
            if (dueDate.text.isNotBlank()) fields["due_date"] = dueDate.text.toString().trim()
            setLoading(submit, true, "Submit reading")
            api.upload("submit_reading.php", fields, "meter_photo", photo, contentResolver) { result ->
                runOnUiThread {
                    setLoading(submit, false, "Submit reading")
                    result.onSuccess { response ->
                        val billId = response.data().optJSONObject("bill")?.optInt("id") ?: 0
                        toast(response.optString("message", "Reading submitted."))
                        if (billId > 0) showBill(billId) else showReadings()
                    }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showCustomers() {
        childScreen()
        val query = input("Name, account or meter number")
        val search = actionButton("Search")
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val form = screen("Customers", "Search customer records by name, account or meter.")
        form.addView(heroBanner(
            title = "Customer search",
            message = "Find accounts quickly and jump into customer detail like the web operations workspace.",
            eyebrow = "Operations",
            tone = toneBlue,
            iconRes = R.drawable.ic_group,
            compact = true
        ))
        form.addView(sectionPanel(
            title = "Search customers",
            description = "Search by name, account number or meter number."
        ) {
            addView(query)
            addView(search)
        })
        form.addView(sectionPanel(
            title = "Results",
            description = "Matching customers appear here with their account and current status."
        ) {
            addView(results)
        })
        addBack(form)
        fun loadCustomers() {
            setLoading(search, true, "Search")
            api.request(api.query("admin/customers.php", mapOf(
                "q" to query.text.toString(),
                "limit" to "100"
            ))) { result ->
                runOnUiThread {
                    setLoading(search, false, "Search")
                    result.onSuccess { response ->
                        results.removeAllViews()
                        val clients = response.data().optJSONArray("customers") ?: JSONArray()
                        if (clients.length() == 0) results.addView(empty("No customers found."))
                        for (index in 0 until clients.length()) {
                            val client = clients.optJSONObject(index) ?: continue
                            val row = card(
                                client.optString("full_name"),
                                listOf(client.optString("account_number"), client.optString("status"), client.optString("phone_number"))
                                    .filter(String::isNotBlank)
                                    .joinToString(" • "),
                                toneForStatus(client.optString("status"), toneBlue)
                            )
                            row.isClickable = true
                            row.isFocusable = true
                            row.setOnClickListener { showAdminCustomer(client.optInt("id")) }
                            results.addView(row)
                        }
                    }.onFailure(::handleError)
                }
            }
        }
        search.setOnClickListener {
            loadCustomers()
        }
        show(form)
        loadCustomers()
    }

    private fun showClientSearch() {
        childScreen()
        val form = screen("Customer search", "Start typing a name, account or meter number to see live suggestions.")
        val (searchBox, _) = searchableIdentifierField("Name, account or meter number") { client ->
            val id = client.optInt("id")
            if (id > 0) showAdminCustomer(id)
        }
        form.addView(searchBox)
        form.addView(body("Tap a suggestion below the field to open that customer directly."))
        addBack(form)
        show(form)
    }

    private fun showAdminCustomer(id: Int) {
        backAction = ::showCustomers
        showLoading("Loading customer")
        api.request(api.query("admin/customer.php", mapOf("id" to id.toString()))) { result ->
            onResult(result, "Could not load customer") { response ->
                val data = response.data()
                val customer = data.optJSONObject("customer") ?: JSONObject()
                val summary = data.optJSONObject("summary") ?: JSONObject()
                val form = screen(customer.optString("full_name"), customer.optString("account_number"))
                val recentBills = data.optJSONArray("recent_bills") ?: JSONArray()
                val recentPayments = data.optJSONArray("recent_payments") ?: JSONArray()
                form.addView(heroBanner(
                    title = customer.optString("full_name").ifBlank { "Customer" },
                    message = listOf(customer.optString("account_number"), customer.optString("status"))
                        .filter(String::isNotBlank)
                        .joinToString(" • "),
                    eyebrow = "Customer detail",
                    tone = toneForStatus(customer.optString("status"), toneBlue),
                    iconRes = R.drawable.ic_person,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Outstanding", money(summary.optDouble("outstanding_amount")), R.drawable.ic_wallet, if (summary.optDouble("outstanding_amount") > 0.01) toneAmber else toneTeal),
                    summaryCard("Bills", recentBills.length().toString(), R.drawable.ic_receipt, toneBlue),
                    summaryCard("Payments", recentPayments.length().toString(), R.drawable.ic_circle_check, toneTeal),
                    summaryCard("Status", customer.optString("status").ifBlank { "Unknown" }, R.drawable.ic_warning_triangle, toneForStatus(customer.optString("status"), toneBlue))
                )))
                form.addView(sectionPanel(
                    title = "Customer profile",
                    description = "Key identity and contact fields arranged like the web customer detail workspace."
                ) {
                    addField(this, "Role", customer.optString("role"))
                    addField(this, "Phone", customer.optString("phone_number"))
                    addField(this, "Email", customer.optString("email"))
                })
                val bills = data.optJSONArray("recent_bills") ?: JSONArray()
                form.addView(sectionPanel(
                    title = "Recent bills",
                    description = "Latest invoice activity for this customer."
                ) {
                    if (bills.length() == 0) {
                        addView(empty("No recent bills found."))
                    } else {
                        for (index in 0 until bills.length()) {
                            val bill = bills.optJSONObject(index) ?: continue
                            val row = card(
                                bill.optString("billing_month"),
                                listOf(money(bill.optDouble("outstanding_amount")) + " due", bill.optString("status"))
                                    .filter(String::isNotBlank)
                                    .joinToString(" • "),
                                toneForStatus(bill.optString("status"), toneBlue)
                            )
                            row.isClickable = true
                            row.isFocusable = true
                            row.setOnClickListener { showAdminBill(bill.optInt("id"), id) }
                            addView(row)
                        }
                    }
                })
                form.addView(sectionPanel(
                    title = "Recent payments",
                    description = "Most recent customer settlements and recorded payment history."
                ) {
                    addPaymentRows(this, recentPayments)
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showAdminBill(id: Int, customerId: Int) {
        backAction = { showAdminCustomer(customerId) }
        showLoading("Loading bill")
        api.request(api.query("admin/bill_detail.php", mapOf("id" to id.toString()))) { result ->
            onResult(result, "Could not load bill") { response ->
                val data = response.data()
                val bill = data.optJSONObject("bill") ?: JSONObject()
                val customer = data.optJSONObject("customer") ?: JSONObject()
                val form = screen("Bill detail", customer.optString("full_name"))
                form.addView(heroBanner(
                    title = "Bill detail #${bill.optInt("id").takeIf { it > 0 } ?: id}",
                    message = listOf(customer.optString("full_name"), bill.optString("account_number"))
                        .filter(String::isNotBlank)
                        .joinToString(" • "),
                    eyebrow = "Billing desk",
                    tone = toneForStatus(bill.optString("status"), toneBlue),
                    iconRes = R.drawable.ic_receipt,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Amount", money(bill.optDouble("amount")), R.drawable.ic_receipt, toneBlue),
                    summaryCard("Outstanding", money(bill.optDouble("outstanding_amount")), R.drawable.ic_wallet, if (bill.optDouble("outstanding_amount") > 0.01) toneAmber else toneTeal),
                    summaryCard("Status", bill.optString("status").ifBlank { "Unknown" }, R.drawable.ic_warning_triangle, toneForStatus(bill.optString("status"), toneBlue)),
                    summaryCard("Month", bill.optString("billing_month").ifBlank { "—" }, R.drawable.ic_circle_clock, toneTeal)
                )))
                form.addView(sectionPanel(
                    title = "Bill summary",
                    description = "Core bill details, aligned with the web bill-detail overview."
                ) {
                    addField(this, "Account", bill.optString("account_number"))
                    addField(this, "Month", bill.optString("billing_month"))
                    addField(this, "Due date", bill.optString("due_date"))
                    addField(this, "Consumption", bill.optString("consumption"))
                })
                form.addView(sectionPanel(
                    title = "Actions",
                    description = "Open the invoice and review linked records from one place."
                ) {
                    addDocumentButton(this, "Open invoice", bill.optString("document_url"))
                })
                form.addView(sectionPanel(
                    title = "Line items",
                    description = "Charge breakdown for the selected bill."
                ) {
                    addJsonRows(this, "Line items", bill.optJSONArray("line_items"), listOf("description", "line_type", "amount"))
                })
                form.addView(sectionPanel(
                    title = "Payments",
                    description = "Payments already allocated to this bill."
                ) {
                    addPaymentRows(this, bill.optJSONArray("payments"))
                })
                form.addView(sectionPanel(
                    title = "Adjustments and approvals",
                    description = "Credit notes and approval activity affecting this bill."
                ) {
                    addJsonRows(this, "Credit notes", bill.optJSONArray("credit_notes"), listOf("type", "amount_credited", "note"))
                    addJsonRows(this, "Approval items", bill.optJSONArray("approval_items"), listOf("title", "status", "amount"))
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showApprovals() {
        childScreen()
        showLoading("Loading approvals")
        api.request("admin/approvals.php?limit=200") { result ->
            onResult(result, "Could not load approvals") { response ->
                val data = response.data()
                val form = screen("Approvals", "Review pending financial requests.")
                val summary = data.optJSONObject("summary")
                val items = data.optJSONArray("items") ?: JSONArray()
                form.addView(heroBanner(
                    title = "Approvals dashboard",
                    message = "Track finance requests and take action from a single workflow queue.",
                    eyebrow = "Workflow",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_circle_check,
                    compact = true
                ))
                if (summary != null) {
                    form.addView(summaryCardGrid(listOf(
                        summaryCard("Pending", summary.opt("pending")?.toString().orEmpty().ifBlank { "0" }, R.drawable.ic_warning_triangle, toneAmber),
                        summaryCard("Approved", summary.opt("approved")?.toString().orEmpty().ifBlank { "0" }, R.drawable.ic_circle_check, toneTeal),
                        summaryCard("Rejected", summary.opt("rejected")?.toString().orEmpty().ifBlank { "0" }, R.drawable.ic_circle_alert, toneRed)
                    )))
                }
                form.addView(sectionPanel(
                    title = "Finance approval items",
                    description = "Pending, approved, and rejected approval requests from the finance workflow."
                ) {
                    if (items.length() == 0) addView(empty("No approval items found."))
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        addView(card(
                            item.optString("title", item.optString("reference_no", "Approval")),
                            listOf(item.optString("status"), money(item.optDouble("amount")), item.optString("submitted_by_name"))
                                .filter(String::isNotBlank)
                                .joinToString(" • "),
                            toneForStatus(item.optString("status"), toneBlue)
                        ))
                        if (item.optString("status").lowercase() == "pending") {
                            addView(buttonRow(
                                "Approve" to { decideApproval(item.optInt("id"), "approve") },
                                "Reject" to { decideApproval(item.optInt("id"), "reject") }
                            ))
                        }
                    }
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun decideApproval(id: Int, action: String) {
        api.request(
            "admin/approvals.php",
            "POST",
            JSONObject().put("item_id", id).put("action", action).put("comments", "")
        ) { result ->
            runOnUiThread {
                result.onSuccess {
                    toast(it.optString("message", "Approval updated."))
                    showApprovals()
                }.onFailure(::handleError)
            }
        }
    }

    private fun showAdminComplaints() {
        childScreen()
        showLoading("Loading complaints")
        api.request("admin/complaints.php") { result ->
            onResult(result, "Could not load complaints") { response ->
                val complaints = response.data().optJSONArray("complaints") ?: JSONArray()
                val form = screen("Manage complaints", "${complaints.length()} complaint(s)")
                if (complaints.length() == 0) form.addView(empty("No complaints found."))
                for (index in 0 until complaints.length()) {
                    val complaint = complaints.optJSONObject(index) ?: continue
                    form.addView(card(
                        complaint.optString("subject"),
                        "${complaint.optString("status")} • ${complaint.optString("full_name")}\n" +
                            complaint.optString("message")
                    ))
                    if (complaint.optString("status") !in setOf("resolved", "closed")) {
                        val progress = secondaryButton("Mark in progress")
                        progress.setOnClickListener {
                            updateComplaint(complaint.optInt("id"), "in_progress")
                        }
                        form.addView(progress)
                        val resolve = actionButton("Resolve")
                        resolve.setOnClickListener {
                            updateComplaint(complaint.optInt("id"), "resolved")
                        }
                        form.addView(resolve)
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun updateComplaint(id: Int, status: String) {
        api.request(
            "admin/complaints.php",
            "POST",
            JSONObject().put("complaint_id", id).put("status", status)
        ) { result ->
            runOnUiThread {
                result.onSuccess {
                    toast(it.optString("message", "Complaint updated."))
                    showAdminComplaints()
                }.onFailure(::handleError)
            }
        }
    }

    private fun showManualPayment() {
        childScreen()
        val (accountBox, account) = searchableIdentifierField("Account or meter number")
        val billId = input("Bill ID (required for invoice)", InputType.TYPE_CLASS_NUMBER)
        val target = dropdownInput(
            "Payment target",
            listOf("invoice" to "Invoice", "balance" to "Account balance"),
            "invoice"
        )
        val amount = input("Amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val method = dropdownInput(
            "Payment method",
            listOf(
                "mpesa" to "M-Pesa",
                "cash" to "Cash",
                "bank" to "Bank",
                "card" to "Card",
                "cheque" to "Cheque",
                "other" to "Other"
            ),
            "mpesa"
        )
        val reference = input("Payment reference")
        val phone = input("Phone number (optional)", InputType.TYPE_CLASS_PHONE)
        val submit = actionButton("Record payment")
        val form = screen("Record payment", "Payments are validated and allocated by the server.")
        form.addView(accountBox)
        listOf(billId, target, amount, method, reference, phone, submit).forEach(form::addView)
        addBack(form)
        submit.setOnClickListener {
            val body = JSONObject()
                .put("account_number", account.text.toString().trim())
                .put("bill_id", billId.text.toString().toIntOrNull() ?: 0)
                .put("payment_target", target.tag as String)
                .put("amount", amount.text.toString().toDoubleOrNull() ?: 0.0)
                .put("payment_method", method.tag as String)
                .put("payment_reference", reference.text.toString().trim())
                .put("phone_number", phone.text.toString().trim())
            AlertDialog.Builder(this)
                .setTitle("Confirm payment")
                .setMessage(
                    "Account: ${account.text}\nAmount: ${money(body.optDouble("amount"))}\n" +
                        "Method: ${method.text}\nTarget: ${target.text}"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Record") { _, _ ->
                    setLoading(submit, true, "Record payment")
                    api.request("admin/manual_payment.php", "POST", body) { result ->
                        runOnUiThread {
                            setLoading(submit, false, "Record payment")
                            result.onSuccess {
                                toast(it.optString("message", "Payment recorded."))
                                showCollections()
                            }.onFailure(::handleError)
                        }
                    }
                }
                .show()
        }
        show(form)
    }

    private fun showCollections(days: Int = 30, limit: Int = 100) {
        childScreen()
        showLoading("Loading collections")
        api.request(api.query("admin/collections.php", mapOf("days" to days.toString(), "limit" to limit.toString()))) { result ->
            onResult(result, "Could not load collections") { response ->
                val data = response.data()
                val summary = data.optJSONObject("summary") ?: JSONObject()
                val fieldMetadata = data.optJSONObject("field_metadata") ?: JSONObject()
                val periodOptions = data.optJSONArray("period_options")?.toFlexibleOptionPairs()
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf("7" to "Last 7 days", "14" to "Last 14 days", "30" to "Last 30 days", "60" to "Last 60 days", "90" to "Last 90 days")
                val limitOptions = fieldMetadata
                    .optJSONObject("limit")
                    ?.optJSONArray("options")
                    ?.toFlexibleOptionPairs()
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf("10" to "10 records", "20" to "20 records", "50" to "50 records", "100" to "100 records")
                val paymentMethodLabels = data.optJSONArray("payment_method_options")?.toFlexibleOptionPairs()?.toMap().orEmpty()
                val periodField = dropdownInput(fieldMetadata.optJSONObject("days")?.optString("label").orEmpty().ifBlank { "Period" }, periodOptions, data.optInt("period_days", days).toString())
                val limitField = dropdownInput(fieldMetadata.optJSONObject("limit")?.optString("label").orEmpty().ifBlank { "Rows" }, limitOptions, limit.toString())
                val form = screen("Collections", "Last ${data.optInt("period_days", 30)} days")
                form.addView(sectionPanel(
                    title = "Collections filters",
                    description = "Use the server-provided period and row controls to inspect collection performance."
                ) {
                    addView(periodField)
                    addView(limitField)
                    addView(buttonRow(
                        "Refresh" to {
                            childScreen()
                            showCollections(
                                periodField.tag?.toString()?.toIntOrNull() ?: data.optInt("period_days", days),
                                limitField.tag?.toString()?.toIntOrNull() ?: limit
                            )
                        },
                        "Last 30 days" to {
                            childScreen()
                            showCollections(30, limitField.tag?.toString()?.toIntOrNull() ?: 100)
                        }
                    ))
                })
                val summaryCards = summaryCardViews(summary, data.optJSONArray("summary_cards"))
                if (summaryCards.isNotEmpty()) {
                    form.addView(summaryCardGrid(summaryCards))
                } else {
                    form.addView(card("Completed", money(summary.optDouble("completed_collected"))))
                    form.addView(card("Pending", money(summary.optDouble("pending_collected"))))
                    form.addView(card("Failed", money(summary.optDouble("failed_collected"))))
                    form.addView(card("Customers served", summary.optInt("customers_served").toString()))
                }
                val methodRows = data.optJSONArray("by_payment_method") ?: JSONArray()
                form.addView(sectionPanel(
                    title = "By payment method",
                    description = "Completed collections grouped using the backend's supported payment method list."
                ) {
                    if (methodRows.length() == 0) {
                        addView(empty("No payment-method totals are available for this period."))
                    }
                    for (index in 0 until methodRows.length()) {
                        val row = methodRows.optJSONObject(index) ?: continue
                        val key = row.optString("payment_method")
                        addView(card(
                            paymentMethodLabels[key] ?: key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                            listOf(
                                "Payments: ${row.optInt("payment_count")}",
                                "Total: ${money(row.optDouble("total_amount"))}"
                            ).joinToString(" • "),
                            toneBlue
                        ))
                    }
                })
                addPaymentRows(form, data.optJSONArray("recent_payments"))
                addBack(form)
                show(form)
            }
        }
    }

    private fun showOnboarding() {
        childScreen()
        showLoading("Loading onboarding")
        api.request("admin/onboarding_tracker.php?limit=100") { result ->
            onResult(result, "Could not load onboarding tracker") { response ->
                val data = response.data()
                val summary = data.optJSONObject("summary") ?: JSONObject()
                val form = screen("Onboarding tracker", "${data.optInt("total")} record(s)")
                form.addView(card("Needs attention", summary.optInt("attention").toString()))
                form.addView(card("Pending payment", (
                    summary.optInt("proforma_pending_payment") +
                        summary.optInt("meter_pending_payment")
                    ).toString()))
                form.addView(card("Completed", summary.optInt("completed").toString()))
                val records = data.optJSONArray("records") ?: JSONArray()
                for (index in 0 until records.length()) {
                    val record = records.optJSONObject(index) ?: continue
                    form.addView(card(
                        record.optString("full_name"),
                        "${record.optString("item_label")} • ${record.optString("overall_label")}\n" +
                            "${money(record.optDouble("outstanding_amount"))} outstanding"
                    ))
                    record.optInt("user_id").takeIf { it > 0 }?.let { userId ->
                        form.addView(secondaryButton("Open customer").apply {
                            setOnClickListener { showAdminCustomer(userId) }
                        })
                    }
                    record.optString("account_number").takeIf(String::isNotBlank)?.let { account ->
                        form.addView(secondaryButton("Open payments").apply {
                            setOnClickListener { loadPaymentsWorkspace(account) }
                        })
                    }
                    addDocumentButton(form, "Open document", record.optString("document_url"))
                    addUrlButton(form, "Share payment link", record.optString("open_url"))
                }
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Demand notices
    // ---------------------------------------------------------------------

    private fun showDemandNotices() {
        childScreen()
        showLoading("Loading demand notices")
        api.request("admin/demand_notices.php?limit=100") { result ->
            onResult(result, "Could not load demand notices") { response ->
                val data = response.data()
                val form = screen("Demand notices", "Overdue billing follow-up")
                renderNode(form, "Summary", data.opt("summary"))
                val generate = actionButton("Generate notices for overdue bills")
                generate.setOnClickListener {
                    setLoading(generate, true, "Generate notices for overdue bills")
                    api.request(
                        "admin/demand_notices.php", "POST",
                        JSONObject().put("action", "generate")
                    ) { r ->
                        runOnUiThread {
                            setLoading(generate, false, "Generate notices for overdue bills")
                            r.onSuccess { toast(it.optString("message", "Notices generated.")); showDemandNotices() }
                                .onFailure(::handleError)
                        }
                    }
                }
                form.addView(generate)
                addRecordList(form, "Recent notices", data.optJSONArray("notices"), listOf("account_number", "full_name")) { row ->
                    showDemandNoticeUpdate(row.optInt("id"), row.optString("status"))
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showDemandNoticeUpdate(noticeId: Int, currentStatus: String) {
        backAction = ::showDemandNotices
        val status = input("New status (e.g. sent, acknowledged, resolved)").apply { setText(currentStatus) }
        val note = multilineInput("Note (optional)")
        val save = actionButton("Update notice")
        val form = screen("Update demand notice", "Notice #$noticeId")
        listOf(status, note, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            if (status.text.isBlank()) {
                toast("Enter a status")
                return@setOnClickListener
            }
            setLoading(save, true, "Update notice")
            api.request(
                "admin/demand_notices.php", "POST",
                JSONObject().put("action", "update_status").put("notice_id", noticeId)
                    .put("status", status.text.toString().trim()).put("note", note.text.toString().trim())
            ) { r ->
                runOnUiThread {
                    setLoading(save, false, "Update notice")
                    r.onSuccess { toast(it.optString("message", "Notice updated.")); showDemandNotices() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Integration health
    // ---------------------------------------------------------------------

    private fun showIntegrationHealth() {
        childScreen()
        showLoading("Loading integration health")
        api.request("admin/integration_health.php") { result ->
            onResult(result, "Could not load integration health") { response ->
                val form = screen("Integration health", "Live status of SMS, M-Pesa, email and eTIMS")
                renderNode(form, "Summary", response.data().opt("summary"))
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Support inquiries
    // ---------------------------------------------------------------------

    private fun showSupportInquiries() {
        childScreen()
        loadSupportInquiries("open")
    }

    private fun showSupportChat() {
        childScreen()
        loadSupportChat(0)
    }

    private fun loadSupportChat(threadId: Int) {
        showLoading("Loading support chat")
        val path = if (threadId > 0) api.query("support_chat.php", mapOf("thread_id" to threadId.toString())) else "support_chat.php"
        api.request(path) { result ->
            onResult(result, "Could not load support chat") { response ->
                val data = response.data()
                val mode = data.optString("mode", "customer")
                val form = screen("Support chat", if (mode == "staff") "Handle customer conversations in real time" else "Chat with the support team")
                val availableAgents = data.optJSONArray("available_agents") ?: JSONArray()
                val currentAvailable = data.optBoolean("current_user_available")
                val selectedThread = data.optJSONObject("thread") ?: JSONObject()
                val threads = data.optJSONArray("threads") ?: JSONArray()
                val messages = data.optJSONArray("messages") ?: JSONArray()

                form.addView(heroBanner(
                    title = if (mode == "staff") "Support desk" else "Live support",
                    message = if (mode == "staff") "${threads.length()} open thread(s) • ${availableAgents.length()} agent(s) available" else "${availableAgents.length()} agent(s) available right now",
                    eyebrow = if (mode == "staff") "Operations" else "Customer care",
                    tone = if (availableAgents.length() > 0) toneTeal else toneAmber,
                    iconRes = R.drawable.ic_support,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard(
                        if (mode == "staff") "Open threads" else "Your threads",
                        threads.length().toString(),
                        R.drawable.ic_support,
                        toneBlue
                    ),
                    summaryCard(
                        "Agents online",
                        availableAgents.length().toString(),
                        R.drawable.ic_circle_check,
                        if (availableAgents.length() > 0) toneTeal else toneAmber
                    ),
                    summaryCard(
                        "Messages",
                        messages.length().toString(),
                        R.drawable.ic_receipt,
                        toneBlue
                    ),
                    summaryCard(
                        "Availability",
                        if (currentAvailable) "Online" else "Offline",
                        R.drawable.ic_circle_alert,
                        if (currentAvailable) toneTeal else toneAmber
                    )
                )))

                if (mode == "staff") {
                    form.addView(buttonRow(
                        (if (currentAvailable) "Set offline" else "Set online") to {
                            api.request(
                                "support_chat.php",
                                "POST",
                                JSONObject().put("action", "set_availability").put("available", !currentAvailable)
                            ) { r ->
                                runOnUiThread {
                                    r.onSuccess { toast(it.optString("message", "Availability updated.")); childScreen(); loadSupportChat(selectedThread.optInt("id")) }
                                        .onFailure(::handleError)
                                }
                            }
                        },
                        "Refresh" to { childScreen(); loadSupportChat(selectedThread.optInt("id")) }
                    ))
                    form.addView(sectionTitle("Open threads"))
                    if (threads.length() == 0) {
                        form.addView(empty("No open support threads."))
                    }
                    for (index in 0 until threads.length()) {
                        val thread = threads.optJSONObject(index) ?: continue
                        val threadView = card(
                            thread.optString("full_name").ifBlank { thread.optString("account_number").ifBlank { "Thread #${thread.optInt("id")}" } },
                            listOf(thread.optString("account_number"), thread.optString("status"), thread.optString("last_message_at"))
                                .filter(String::isNotBlank)
                                .joinToString(" • "),
                            if (thread.optInt("id") == selectedThread.optInt("id")) toneTeal else toneNeutral
                        )
                        threadView.isClickable = true
                        threadView.isFocusable = true
                        threadView.setOnClickListener { childScreen(); loadSupportChat(thread.optInt("id")) }
                        form.addView(threadView)
                    }
                } else {
                    val agentNames = (0 until availableAgents.length()).mapNotNull { index ->
                        availableAgents.optJSONObject(index)?.optString("full_name")?.takeIf(String::isNotBlank)
                            ?: availableAgents.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)
                    }
                    form.addView(statusBanner(
                        if (agentNames.isNotEmpty()) "Support is online" else "Support may be offline",
                        agentNames.joinToString(", ").ifEmpty { "You can still send a message and check back later." },
                        if (agentNames.isNotEmpty()) toneTeal else toneAmber,
                        R.drawable.ic_support,
                        false
                    ))
                }

                form.addView(sectionTitle("Conversation"))
                selectedThread.optString("status").takeIf(String::isNotBlank)?.let { status ->
                    form.addView(statusBanner(
                        "Thread status",
                        listOf(
                            selectedThread.optString("full_name"),
                            selectedThread.optString("account_number"),
                            status
                        ).filter(String::isNotBlank).joinToString(" • "),
                        toneForStatus(status, toneBlue),
                        R.drawable.ic_circle_alert,
                        false
                    ))
                }
                if (messages.length() == 0) {
                    form.addView(empty("No messages yet. Start the conversation below."))
                }
                for (index in 0 until messages.length()) {
                    val message = messages.optJSONObject(index) ?: continue
                    form.addView(chatMessageCard(message, mode == "staff", currentUser?.optInt("id") ?: 0))
                }

                val composer = multilineInput(if (mode == "staff") "Reply to this customer" else "Type your message")
                val send = actionButton("Send message")
                form.addView(composer)
                form.addView(buttonRow(
                    "Refresh" to { childScreen(); loadSupportChat(selectedThread.optInt("id")) },
                    "Close thread" to {
                        if (mode != "staff") {
                            toast("Only support staff can close threads.")
                        } else {
                            api.request(
                                "support_chat.php",
                                "POST",
                                JSONObject().put("action", "close_thread").put("thread_id", selectedThread.optInt("id"))
                            ) { r ->
                                runOnUiThread {
                                    r.onSuccess { toast(it.optString("message", "Thread closed.")); childScreen(); loadSupportChat(0) }
                                        .onFailure(::handleError)
                                }
                            }
                        }
                    }
                ))
                form.addView(send)
                addBack(form)
                send.setOnClickListener {
                    val activeThreadId = selectedThread.optInt("id")
                    if (activeThreadId <= 0 || composer.text.isBlank()) {
                        toast("Open a thread and enter a message.")
                        return@setOnClickListener
                    }
                    setLoading(send, true, "Send message")
                    api.request(
                        "support_chat.php",
                        "POST",
                        JSONObject().put("action", "send_message").put("thread_id", activeThreadId).put("message", composer.text.toString().trim())
                    ) { r ->
                        runOnUiThread {
                            setLoading(send, false, "Send message")
                            r.onSuccess { toast(it.optString("message", "Message sent.")); childScreen(); loadSupportChat(activeThreadId) }
                                .onFailure(::handleError)
                        }
                    }
                }
                show(form)
            }
        }
    }

    private fun chatMessageCard(message: JSONObject, isStaffDesk: Boolean, currentUserId: Int) = card(
        when {
            message.optString("sender_type") == "admin" && (isStaffDesk || message.optInt("sender_id") == currentUserId) -> "You"
            message.optString("sender_type") == "admin" -> "Support"
            isStaffDesk -> "Customer"
            else -> "You"
        },
        listOf(
            message.optString("message"),
            message.optString("created_at")
        ).filter(String::isNotBlank).joinToString("\n"),
        when {
            message.optString("sender_type") == "admin" && (isStaffDesk || message.optInt("sender_id") == currentUserId) -> toneBlue
            message.optString("sender_type") == "admin" -> toneTeal
            isStaffDesk -> toneAmber
            else -> toneBlue
        }
    )

    private fun showMessagingCenter() {
        childScreen()
        loadMessagingCenter()
    }

    private fun loadMessagingCenter() {
        showLoading("Loading messaging center")
        api.request("admin/messaging.php") { result ->
            onResult(result, "Could not load messaging center") { response ->
                val data = response.data()
                val form = screen("Messaging center", "SMS broadcasts, availability and internal team chat")
                val availability = data.optJSONObject("availability") ?: JSONObject()
                val currentAvailable = availability.optBoolean("current_user_available")
                val internalMessages = data.optJSONArray("internal_messages") ?: JSONArray()
                val recentBroadcasts = data.optJSONArray("recent_broadcasts") ?: JSONArray()
                val clientTemplates = data.optJSONArray("client_templates") ?: JSONArray()
                val staffTemplates = data.optJSONArray("staff_templates") ?: JSONArray()

                form.addView(heroBanner(
                    title = "Messaging center",
                    message = "${internalMessages.length()} internal message(s) • ${recentBroadcasts.length()} recent broadcast(s)",
                    eyebrow = "Communications",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_support,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Team chat", internalMessages.length().toString(), R.drawable.ic_support, toneBlue),
                    summaryCard("Broadcasts", recentBroadcasts.length().toString(), R.drawable.ic_receipt, toneTeal),
                    summaryCard("Templates", (clientTemplates.length() + staffTemplates.length()).toString(), R.drawable.ic_circle_check, toneBlue),
                    summaryCard("Availability", if (currentAvailable) "Online" else "Offline", R.drawable.ic_circle_alert, if (currentAvailable) toneTeal else toneAmber)
                )))
                form.addView(buttonRow(
                    (if (currentAvailable) "Set offline" else "Set online") to {
                        api.request(
                            "admin/messaging.php",
                            "POST",
                            JSONObject().put("action", "set_availability").put("available", !currentAvailable)
                        ) { r ->
                            runOnUiThread {
                                r.onSuccess { toast(it.optString("message", "Availability updated.")); childScreen(); loadMessagingCenter() }
                                    .onFailure(::handleError)
                            }
                        }
                    },
                    "Compose broadcast" to { showBroadcastComposer(data) }
                ))

                form.addView(sectionTitle("Internal team chat"))
                if (internalMessages.length() == 0) form.addView(empty("No internal messages yet."))
                for (index in 0 until internalMessages.length()) {
                    val message = internalMessages.optJSONObject(index) ?: continue
                    form.addView(card(
                        listOf(message.optString("full_name"), message.optString("role").replaceFirstChar(Char::uppercase))
                            .filter(String::isNotBlank)
                            .joinToString(" • ")
                            .ifBlank { "Staff update" },
                        listOf(message.optString("message"), message.optString("created_at")).filter(String::isNotBlank).joinToString("\n"),
                        toneBlue
                    ))
                }
                val internalMessage = multilineInput("Send a team update")
                val sendInternal = actionButton("Send internal message")
                form.addView(internalMessage)
                form.addView(sendInternal)
                sendInternal.setOnClickListener {
                    if (internalMessage.text.isBlank()) {
                        toast("Enter a message")
                        return@setOnClickListener
                    }
                    setLoading(sendInternal, true, "Send internal message")
                    api.request(
                        "admin/messaging.php",
                        "POST",
                        JSONObject().put("action", "send_internal_message").put("message", internalMessage.text.toString().trim())
                    ) { r ->
                        runOnUiThread {
                            setLoading(sendInternal, false, "Send internal message")
                            r.onSuccess { toast(it.optString("message", "Internal message sent.")); childScreen(); loadMessagingCenter() }
                                .onFailure(::handleError)
                        }
                    }
                }

                addRecordList(form, "Recent broadcasts", recentBroadcasts, listOf("subject", "sender_name"))
                addBack(form)
                show(form)
            }
        }
    }

    private fun showBroadcastComposer(data: JSONObject) {
        backAction = ::showMessagingCenter
        val clientTemplates = data.optJSONArray("client_templates") ?: JSONArray()
        val staffTemplates = data.optJSONArray("staff_templates") ?: JSONArray()
        val templateOptions = listOf("" to "No template") +
            (0 until clientTemplates.length()).mapNotNull { index ->
                clientTemplates.optJSONObject(index)?.let { template ->
                    "clients:${template.optInt("id")}" to "Client • ${template.optString("title").ifBlank { template.optString("subject") }}"
                }
            } +
            (0 until staffTemplates.length()).mapNotNull { index ->
                staffTemplates.optJSONObject(index)?.let { template ->
                    "staff:${template.optInt("id")}" to "Staff • ${template.optString("title").ifBlank { template.optString("subject") }}"
                }
            }
        val recipientGroup = dropdownInput("Recipient group", listOf("clients" to "Clients", "staff" to "Staff"), "clients")
        val audience = dropdownInput(
            "Audience",
            listOf(
                "all_clients" to "All active clients",
                "all_staff" to "All active staff",
                "selected_clients" to "Selected client IDs",
                "selected_staff" to "Selected staff IDs"
            ),
            "all_clients"
        )
        val template = dropdownInput("Template", templateOptions, "")
        val subject = input("Subject")
        val message = multilineInput("Message")
        val selectedIds = input("Selected IDs (comma separated, optional)")
        val saveTemplateTitle = input("Template name to save (optional)")
        val applyTemplate = secondaryButton("Apply selected template")
        val send = actionButton("Send broadcast")
        val saveTemplate = secondaryButton("Save as template")
        val form = screen("Compose broadcast", "Send SMS notifications like the web messaging center")
        listOf(recipientGroup, audience, template, applyTemplate, subject, message, selectedIds, saveTemplateTitle, send, saveTemplate).forEach(form::addView)
        addBack(form)

        applyTemplate.setOnClickListener {
            val selected = template.tag?.toString().orEmpty()
            val source = when {
                selected.startsWith("clients:") -> clientTemplates
                selected.startsWith("staff:") -> staffTemplates
                else -> null
            }
            val templateId = selected.substringAfter(':', "0").toIntOrNull() ?: 0
            val templateRow = source?.let { rows ->
                (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index) }.firstOrNull { it.optInt("id") == templateId }
            }
            if (templateRow != null) {
                subject.setText(templateRow.optString("subject"))
                message.setText(templateRow.optString("message"))
                recipientGroup.tag = if (selected.startsWith("staff:")) "staff" else "clients"
                recipientGroup.setText(if (selected.startsWith("staff:")) "Staff" else "Clients")
            }
        }

        send.setOnClickListener {
            if (subject.text.isBlank() || message.text.isBlank()) {
                toast("Enter a subject and message.")
                return@setOnClickListener
            }
            val parsedIds = selectedIds.text.toString().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
            setLoading(send, true, "Send broadcast")
            api.request(
                "admin/messaging.php",
                "POST",
                JSONObject()
                    .put("action", "send_broadcast")
                    .put("recipient_group", recipientGroup.tag?.toString().orEmpty())
                    .put("audience", audience.tag?.toString().orEmpty())
                    .put("subject", subject.text.toString().trim())
                    .put("message", message.text.toString().trim())
                    .put("selected_ids", JSONArray(parsedIds))
            ) { r ->
                runOnUiThread {
                    setLoading(send, false, "Send broadcast")
                    r.onSuccess { toast(it.optString("message", "Broadcast sent.")); showMessagingCenter() }
                        .onFailure(::handleError)
                }
            }
        }

        saveTemplate.setOnClickListener {
            if (saveTemplateTitle.text.isBlank() || subject.text.isBlank() || message.text.isBlank()) {
                toast("Enter a template name, subject and message.")
                return@setOnClickListener
            }
            setLoading(saveTemplate, true, "Save as template")
            api.request(
                "admin/messaging.php",
                "POST",
                JSONObject()
                    .put("action", "save_template")
                    .put("recipient_group", recipientGroup.tag?.toString().orEmpty())
                    .put("template_title", saveTemplateTitle.text.toString().trim())
                    .put("subject", subject.text.toString().trim())
                    .put("message", message.text.toString().trim())
            ) { r ->
                runOnUiThread {
                    setLoading(saveTemplate, false, "Save as template")
                    r.onSuccess { toast(it.optString("message", "Template saved.")); showMessagingCenter() }
                        .onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun loadSupportInquiries(status: String) {
        showLoading("Loading support inquiries")
        api.request(api.query("admin/support_inquiries.php", mapOf("status" to status, "limit" to "100"))) { result ->
            onResult(result, "Could not load support inquiries") { response ->
                val data = response.data()
                val summary = data.optJSONObject("summary") ?: JSONObject()
                val form = screen(
                    "Support inquiries",
                    "Open ${summary.optInt("open")} • Handled ${summary.optInt("handled")} • Total ${summary.optInt("all")}"
                )
                form.addView(buttonRow(
                    "Open" to { childScreen(); loadSupportInquiries("open") },
                    "Handled" to { childScreen(); loadSupportInquiries("handled") },
                    "All" to { childScreen(); loadSupportInquiries("all") }
                ))
                val inquiries = data.optJSONArray("inquiries") ?: JSONArray()
                if (inquiries.length() == 0) form.addView(empty("No inquiries found."))
                for (index in 0 until inquiries.length()) {
                    val inquiry = inquiries.optJSONObject(index) ?: continue
                    val id = inquiry.optInt("id")
                    form.addView(card(
                        "${inquiry.optString("name")} • ${inquiry.optString("status")}",
                        "${inquiry.optString("email")} • ${inquiry.optString("phone")}\n${inquiry.optString("message")}"
                    ))
                    val reply = secondaryButton("Reply by email")
                    reply.setOnClickListener { showSupportInquiryReply(id) }
                    form.addView(reply)
                    if (inquiry.optString("status") != "handled") {
                        form.addView(buttonRow(
                            "Mark handled" to {
                                postAction(
                                    "admin/support_inquiries.php",
                                    JSONObject().put("action", "mark_handled").put("inquiry_id", id)
                                ) { loadSupportInquiries(status) }
                            },
                            "Delete" to {
                                postAction(
                                    "admin/support_inquiries.php",
                                    JSONObject().put("action", "delete").put("inquiry_id", id)
                                ) { loadSupportInquiries(status) }
                            }
                        ))
                    } else {
                        form.addView(buttonRow(
                            "Reopen" to {
                                postAction(
                                    "admin/support_inquiries.php",
                                    JSONObject().put("action", "reopen").put("inquiry_id", id)
                                ) { loadSupportInquiries(status) }
                            },
                            "Delete" to {
                                postAction(
                                    "admin/support_inquiries.php",
                                    JSONObject().put("action", "delete").put("inquiry_id", id)
                                ) { loadSupportInquiries(status) }
                            }
                        ))
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showSupportInquiryReply(id: Int) {
        backAction = ::showSupportInquiries
        val subject = input("Reply subject")
        val message = multilineInput("Reply message")
        val send = actionButton("Send reply")
        val form = screen("Reply to inquiry", "Inquiry #$id")
        listOf(subject, message, send).forEach(form::addView)
        addBack(form)
        send.setOnClickListener {
            if (subject.text.isBlank() || message.text.isBlank()) {
                toast("Enter subject and message")
                return@setOnClickListener
            }
            setLoading(send, true, "Send reply")
            api.request(
                "admin/support_inquiries.php", "POST",
                JSONObject().put("action", "reply_email").put("inquiry_id", id)
                    .put("reply_subject", subject.text.toString().trim())
                    .put("reply_message", message.text.toString().trim())
            ) { r ->
                runOnUiThread {
                    setLoading(send, false, "Send reply")
                    r.onSuccess { toast(it.optString("message", "Reply sent.")); showSupportInquiries() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Activity logs
    // ---------------------------------------------------------------------

    private fun showActivityLogs() {
        childScreen()
        showLoading("Loading activity logs")
        api.request("admin/activity_logs.php?limit=50") { result ->
            onResult(result, "Could not load activity logs") { response ->
                val data = response.data()
                val logs = data.optJSONArray("logs") ?: JSONArray()
                val total = data.optJSONObject("pagination")?.optInt("total") ?: logs.length()
                val form = screen("Activity logs", "$total entrie(s)")
                if (logs.length() == 0) form.addView(empty("No activity logs found."))
                for (index in 0 until logs.length()) {
                    val log = logs.optJSONObject(index) ?: continue
                    val id = log.optInt("id")
                    form.addView(card(
                        "${log.optString("action")} • ${log.optString("full_name", "System")}",
                        "${log.optString("entity_type")} #${log.optInt("entity_id")}\n${log.optString("description")}\n${log.optString("created_at")}"
                    ))
                    val delete = destructiveButton("Delete entry")
                    delete.setOnClickListener {
                        postAction(
                            "admin/activity_logs.php",
                            JSONObject().put("action", "delete_selected").put("log_ids", JSONArray().put(id))
                        ) { showActivityLogs() }
                    }
                    form.addView(delete)
                }
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // System logs
    // ---------------------------------------------------------------------

    private fun showSystemLogs() {
        childScreen()
        showLoading("Loading system logs")
        api.request("admin/system_logs.php") { result ->
            onResult(result, "Could not load system logs") { response ->
                val data = response.data()
                val form = screen("System logs", "Error logs and SMS queue status")
                val errors = data.optJSONArray("error_logs") ?: JSONArray()
                val smsQueue = data.optJSONObject("sms_queue") ?: JSONObject()
                val pendingSms = smsQueue.optJSONArray("pending")?.length() ?: 0
                form.addView(heroBanner(
                    title = if (errors.length() > 0) "System attention needed" else "System queues look stable",
                    message = "${errors.length()} error log(s) • $pendingSms pending SMS item(s)",
                    eyebrow = "Operations",
                    tone = if (errors.length() > 0) toneAmber else toneTeal,
                    iconRes = if (errors.length() > 0) R.drawable.ic_warning_triangle else R.drawable.ic_circle_check,
                    compact = true
                ))
                form.addView(sectionTitle("Error logs (${errors.length()})"))
                if (errors.length() == 0) form.addView(empty("No error logs found."))
                for (index in 0 until errors.length()) {
                    val err = errors.optJSONObject(index) ?: continue
                    val id = err.optInt("id")
                    val errorMessage = err.optString("error_message").ifBlank { err.optString("message") }
                    form.addView(card(err.optString("service", "Error"), "${errorMessage}\n${err.optString("created_at")}"))
                    val delete = destructiveButton("Delete this log")
                    delete.setOnClickListener {
                        postAction(
                            "admin/system_logs.php",
                            JSONObject().put("action", "delete_selected_errors").put("log_ids", JSONArray().put(id))
                        ) { showSystemLogs() }
                    }
                    form.addView(delete)
                }
                if (errors.length() > 0) {
                    val deleteAll = destructiveButton("Delete all error logs")
                    deleteAll.setOnClickListener {
                        postAction("admin/system_logs.php", JSONObject().put("action", "delete_all_errors")) { showSystemLogs() }
                    }
                    form.addView(deleteAll)
                }
                renderNode(form, "Error stats (last 24h)", data.opt("error_stats"))
                listOf("pending" to "Pending SMS", "sent" to "Sent SMS", "failed_permanent" to "Failed SMS").forEach { (key, label) ->
                    val rows = smsQueue.optJSONArray(key) ?: JSONArray()
                    form.addView(sectionTitle("$label (${rows.length()})"))
                    if (rows.length() == 0) form.addView(empty("No records."))
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        val id = row.optInt("id")
                        form.addView(card(row.optString("phone", "SMS"), "${row.optString("message")}\n${row.optString("status")} • ${row.optString("created_at")}"))
                        val buttons = mutableListOf<Pair<String, () -> Unit>>()
                        if (key != "sent") {
                            buttons.add("Retry now" to {
                                postAction("admin/system_logs.php", JSONObject().put("action", "retry_sms_now").put("sms_id", id)) { showSystemLogs() }
                            })
                        }
                        buttons.add("Delete" to {
                            postAction(
                                "admin/system_logs.php",
                                JSONObject().put("action", "delete_sms_selected").put("sms_status", key).put("sms_ids", JSONArray().put(id))
                            ) { showSystemLogs() }
                        })
                        form.addView(buttonRow(*buttons.toTypedArray()))
                    }
                    if (rows.length() > 0) {
                        val clearStatus = secondaryButton("Clear all $label")
                        clearStatus.setOnClickListener {
                            postAction("admin/system_logs.php", JSONObject().put("action", "delete_sms_status").put("sms_status", key)) { showSystemLogs() }
                        }
                        form.addView(clearStatus)
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Blog
    // ---------------------------------------------------------------------

    private fun showBlog() {
        childScreen()
        showLoading("Loading blog")
        api.request("admin/blog.php?limit=50") { result ->
            onResult(result, "Could not load blog") { response ->
                val data = response.data()
                val canWrite = data.optBoolean("can_write")
                val form = screen("Blog management", if (canWrite) "Create and moderate posts" else "View posts and comments")
                if (canWrite) {
                    val newPost = actionButton("New post")
                    newPost.setOnClickListener { showBlogPostEditor(null) }
                    form.addView(newPost)
                }
                val posts = data.optJSONArray("posts") ?: JSONArray()
                form.addView(sectionTitle("Posts (${posts.length()})"))
                if (posts.length() == 0) form.addView(empty("No posts found."))
                for (index in 0 until posts.length()) {
                    val post = posts.optJSONObject(index) ?: continue
                    val row = card(post.optString("title"), "${post.optString("status")} • ${post.optString("created_at")}")
                    if (canWrite) {
                        row.isClickable = true
                        row.isFocusable = true
                        row.setOnClickListener { showBlogPostEditor(post) }
                    }
                    form.addView(row)
                    if (canWrite) {
                        val delete = destructiveButton("Delete post")
                        val postId = post.optInt("id")
                        delete.setOnClickListener {
                            postAction("admin/blog.php", JSONObject().put("action", "delete_post").put("post_id", postId)) { showBlog() }
                        }
                        form.addView(delete)
                    }
                }
                val comments = data.optJSONArray("pending_comments") ?: JSONArray()
                form.addView(sectionTitle("Pending comments (${comments.length()})"))
                if (comments.length() == 0) form.addView(empty("No pending comments."))
                for (index in 0 until comments.length()) {
                    val comment = comments.optJSONObject(index) ?: continue
                    form.addView(card(comment.optString("author_name", "Comment"), comment.optString("comment_text", comment.optString("body"))))
                    if (canWrite) {
                        val id = comment.optInt("id")
                        form.addView(buttonRow(
                            "Approve" to {
                                postAction("admin/blog.php", JSONObject().put("action", "comment_status").put("comment_id", id).put("status", "approved")) { showBlog() }
                            },
                            "Reject" to {
                                postAction("admin/blog.php", JSONObject().put("action", "comment_status").put("comment_id", id).put("status", "rejected")) { showBlog() }
                            },
                            "Delete" to {
                                postAction("admin/blog.php", JSONObject().put("action", "delete_comment").put("comment_id", id)) { showBlog() }
                            }
                        ))
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showBlogPostEditor(post: JSONObject?) {
        backAction = ::showBlog
        val title = input("Title").apply { setText(post?.optString("title") ?: "") }
        val excerpt = input("Excerpt (optional)").apply { setText(post?.optString("excerpt") ?: "") }
        val coverImage = input("Cover image URL (optional)").apply { setText(post?.optString("cover_image") ?: "") }
        val bodyField = multilineInput("Post body").apply { setText(post?.optString("body") ?: "") }
        val status = input("Status: draft or published").apply {
            setText(post?.optString("status")?.takeIf(String::isNotBlank) ?: "draft")
        }
        val save = actionButton(if (post == null) "Create post" else "Save post")
        val form = screen(if (post == null) "New blog post" else "Edit blog post", "")
        listOf(title, excerpt, coverImage, bodyField, status, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            if (title.text.isBlank() || bodyField.text.isBlank()) {
                toast("Title and body are required.")
                return@setOnClickListener
            }
            val statusValue = status.text.toString().trim().lowercase().ifBlank { "draft" }
            setLoading(save, true, if (post == null) "Create post" else "Save post")
            api.request(
                "admin/blog.php", "POST",
                JSONObject().put("action", "save_post").put("post_id", post?.optInt("id") ?: 0)
                    .put("title", title.text.toString().trim()).put("body", bodyField.text.toString())
                    .put("status", statusValue).put("excerpt", excerpt.text.toString().trim())
                    .put("cover_image", coverImage.text.toString().trim())
            ) { r ->
                runOnUiThread {
                    setLoading(save, false, if (post == null) "Create post" else "Save post")
                    r.onSuccess { toast(it.optString("message", "Post saved.")); showBlog() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Accounting overview
    // ---------------------------------------------------------------------

    private fun showAccountingOverview() {
        childScreen()
        showLoading("Loading accounting overview")
        api.request("admin/accounting_overview.php") { result ->
            onResult(result, "Could not load accounting overview") { response ->
                val data = response.data()
                val form = screen("Accounting overview", "Chart of accounts, trial balance and reconciliation")
                form.addView(heroBanner(
                    title = "Accounting control",
                    message = "Manage the chart of accounts, review balances and post journals from one workspace.",
                    eyebrow = "Finance",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_receipt,
                    compact = true
                ))
                renderFinanceSummary(form, data.optJSONObject("summary"))
                val accounts = data.optJSONArray("accounts") ?: JSONArray()
                form.addView(quickActionGrid(listOf(
                    Triple("New account", R.drawable.ic_circle_check) { showAccountEditor(null, accounts) },
                    Triple("Post journal", R.drawable.ic_receipt) { showPostJournalEntry(accounts) },
                    Triple("Open ledger", R.drawable.ic_wallet) { showAccountingLedger() },
                    Triple("Lock period", R.drawable.ic_circle_alert) { showPeriodLockForm() }
                )))
                form.addView(sectionTitle("Account management"))
                val newAccount = actionButton("New account")
                newAccount.setOnClickListener { showAccountEditor(null, accounts) }
                form.addView(newAccount)
                form.addView(sectionTitle("Chart of accounts (${accounts.length()})"))
                if (accounts.length() == 0) form.addView(empty("No accounts found."))
                for (index in 0 until accounts.length()) {
                    val account = accounts.optJSONObject(index) ?: continue
                    val active = account.optBoolean("is_active", true)
                    val row = card(
                        "${account.optString("code")} • ${account.optString("name")}",
                        "${formatAccountType(account.optString("account_type"))} • ${if (active) "Active" else "Inactive"}",
                        if (active) toneBlue else toneNeutral
                    )
                    row.isClickable = true
                    row.isFocusable = true
                    row.setOnClickListener { showAccountEditor(account, accounts) }
                    form.addView(row)
                }
                addRecordList(form, "Trial balance", data.optJSONArray("trial_balance"), listOf("name", "code"))
                renderFinanceNode(form, "Reconciliation", data.opt("reconciliation"))
                addRecordList(form, "Period locks", data.optJSONArray("period_locks"), listOf("period_key"))
                form.addView(sectionTitle("Journal entries"))
                val journal = actionButton("Post journal entry")
                journal.setOnClickListener { showPostJournalEntry(accounts) }
                form.addView(journal)
                val periodForm = actionButton("Lock / unlock accounting period")
                periodForm.setOnClickListener { showPeriodLockForm() }
                form.addView(periodForm)
                show(form)
            }
        }
    }

    private fun showAccountEditor(account: JSONObject?, accounts: JSONArray) {
        backAction = ::showAccountingOverview
        val code = input("Account code").apply { setText(account?.optString("code") ?: "") }
        val name = input("Account name").apply { setText(account?.optString("name") ?: "") }
        val accountTypes = listOf(
            "asset" to "Asset",
            "liability" to "Liability",
            "equity" to "Equity",
            "revenue" to "Revenue",
            "expense" to "Expense",
            "cost_of_sales" to "Cost of sales"
        ).toMutableList()
        val existingType = account?.optString("account_type").orEmpty()
        if (existingType.isNotBlank() && accountTypes.none { it.first == existingType }) {
            accountTypes += existingType to existingType.replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
        val type = dropdownInput(
            "Account type",
            accountTypes,
            existingType.ifBlank { "asset" }
        )
        val normalBalance = dropdownInput(
            "Normal balance",
            listOf("debit" to "Debit", "credit" to "Credit"),
            account?.optString("normal_balance")?.takeIf(String::isNotBlank) ?: "debit"
        )
        val parentOptions = listOf("0" to "No parent account") +
            accountOptions(accounts).filter { it.first != account?.optInt("id")?.toString() }
        val parentId = dropdownInput(
            "Parent account (optional)",
            parentOptions,
            account?.optInt("parent_id")?.takeIf { it > 0 }?.toString() ?: "0"
        )
        val description = input("Description (optional)").apply { setText(account?.optString("description") ?: "") }
        val active = CheckBox(this).apply {
            text = getString(R.string.active_label)
            isChecked = account?.optBoolean("is_active", true) ?: true
            setTextColor(textPrimary)
        }
        val save = actionButton(if (account == null) "Create account" else "Save account")
        val form = screen(if (account == null) "New account" else "Edit account", account?.optString("code") ?: "")
        listOf(code, name, type, normalBalance, parentId, description, active, save).forEach(form::addView)
        if (account != null) {
            val isActiveNow = account.optBoolean("is_active", true)
            val toggle = secondaryButton(if (isActiveNow) "Deactivate account" else "Activate account")
            toggle.setOnClickListener {
                postAction(
                    "admin/accounting_overview.php",
                    JSONObject().put("action", "toggle_account").put("account_id", account.optInt("id")).put("is_active", !isActiveNow)
                ) { showAccountingOverview() }
            }
            form.addView(toggle)
        }
        save.setOnClickListener {
            if (code.text.isBlank() || name.text.isBlank() || type.text.isBlank()) {
                toast("Code, name and type are required.")
                return@setOnClickListener
            }
            val body = JSONObject()
                .put("action", "save_account")
                .put("account_id", account?.optInt("id") ?: 0)
                .put("code", code.text.toString().trim())
                .put("name", name.text.toString().trim())
                .put("account_type", type.tag?.toString() ?: "asset")
                .put("normal_balance", normalBalance.tag?.toString() ?: "debit")
                .put("parent_id", parentId.tag?.toString()?.toIntOrNull() ?: 0)
                .put("description", description.text.toString().trim())
                .put("is_active", active.isChecked)
            setLoading(save, true, if (account == null) "Create account" else "Save account")
            api.request("admin/accounting_overview.php", "POST", body) { r ->
                runOnUiThread {
                    setLoading(save, false, if (account == null) "Create account" else "Save account")
                    r.onSuccess { toast(it.optString("message", "Account saved.")); showAccountingOverview() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showPostJournalEntry(accounts: JSONArray) {
        backAction = ::showAccountingOverview
        val accountOptions = accountOptions(accounts)
        if (accountOptions.size < 2) {
            val form = screen("Post journal entry", "Choose accounts by name. Debits must equal credits.")
            form.addView(heroBanner(
                title = "Journal entry unavailable",
                message = "At least two active accounts are required before you can post a journal entry.",
                eyebrow = "Finance",
                tone = toneAmber,
                iconRes = R.drawable.ic_warning_triangle,
                compact = true
            ))
            form.addView(empty("Create or activate more accounts, then try again."))
            addBack(form)
            show(form)
            return
        }
        val entryDate = datePickerInput("Entry date", today())
        val memo = input("Memo")
        val debitAccountId = dropdownInput("Debit account", accountOptions, accountOptions.firstOrNull()?.first.orEmpty())
        val creditAccountId = dropdownInput("Credit account", accountOptions, accountOptions.getOrNull(1)?.first ?: accountOptions.firstOrNull()?.first.orEmpty())
        val amount = input("Amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val referenceType = input("Reference type (optional)").apply { setText(R.string.journal_reference_manual) }
        val post = actionButton("Post journal entry")
        val form = screen("Post journal entry", "Choose accounts by name. Debits must equal credits.")
        listOf(entryDate, memo, debitAccountId, creditAccountId, amount, referenceType, post).forEach(form::addView)
        post.setOnClickListener {
            val debitId = debitAccountId.tag?.toString()?.toIntOrNull() ?: 0
            val creditId = creditAccountId.tag?.toString()?.toIntOrNull() ?: 0
            if (debitId == 0 || creditId == 0 || debitId == creditId || amount.text.isBlank()) {
                toast("Choose two different accounts and enter an amount.")
                return@setOnClickListener
            }
            setLoading(post, true, "Post journal entry")
            api.request(
                "admin/accounting_overview.php", "POST",
                JSONObject().put("action", "post_entry")
                    .put("entry_date", entryDate.text.toString().trim())
                    .put("memo", memo.text.toString().trim())
                    .put("debit_account_id", debitId)
                    .put("credit_account_id", creditId)
                    .put("amount", amount.text.toString().toDoubleOrNull() ?: 0.0)
                    .put("reference_type", referenceType.text.toString().trim())
            ) { r ->
                runOnUiThread {
                    setLoading(post, false, "Post journal entry")
                    r.onSuccess { toast(it.optString("message", "Journal entry posted.")); showAccountingOverview() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showPeriodLockForm() {
        backAction = ::showAccountingOverview
        val periodKey = input("Period key (e.g. 2026-09)")
        val note = multilineInput("Note (optional)")
        val lock = actionButton("Lock period")
        val unlock = secondaryButton("Unlock period")
        val form = screen("Lock / unlock period", "")
        listOf(periodKey, note, lock, unlock).forEach(form::addView)
        lock.setOnClickListener {
            if (periodKey.text.isBlank()) {
                toast("Enter a period key.")
                return@setOnClickListener
            }
            postAction(
                "admin/accounting_overview.php",
                JSONObject().put("action", "lock_period").put("period_key", periodKey.text.toString().trim()).put("note", note.text.toString().trim())
            ) { showAccountingOverview() }
        }
        unlock.setOnClickListener {
            if (periodKey.text.isBlank()) {
                toast("Enter a period key.")
                return@setOnClickListener
            }
            postAction(
                "admin/accounting_overview.php",
                JSONObject().put("action", "unlock_period").put("period_key", periodKey.text.toString().trim()).put("note", note.text.toString().trim())
            ) { showAccountingOverview() }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Accounting reports
    // ---------------------------------------------------------------------

    private fun showAccountingReports() {
        childScreen()
        showLoading("Loading accounting reports")
        api.request("admin/accounting_reports.php") { result ->
            onResult(result, "Could not load accounting reports") { response ->
                val data = response.data()
                val form = screen("Accounting reports", "Balance sheet, P&L, cash flow and AR aging")
                form.addView(heroBanner(
                    title = "Financial reports",
                    message = "Review statements and receivables in a structured finance view instead of raw text output.",
                    eyebrow = "Reporting",
                    tone = toneTeal,
                    iconRes = R.drawable.ic_wallet,
                    compact = true
                ))
                form.addView(sectionTitle("Financial statements"))
                renderFinanceNode(form, "Balance sheet", data.opt("balance_sheet"))
                renderFinanceNode(form, "Profit and loss", data.opt("profit_and_loss"))
                form.addView(sectionTitle("Cash & receivables"))
                renderFinanceNode(form, "Cash flow", data.opt("cash_flow"))
                renderFinanceNode(form, "Accounts receivable aging", data.opt("accounts_receivable_aging"))
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Accounting ledger
    // ---------------------------------------------------------------------

    private fun showAccountingLedger() {
        childScreen()
        loadAccountingLedger(0, 0, "")
    }

    private fun loadAccountingLedger(accountId: Int, entryId: Int, accountType: String) {
        showLoading("Loading accounting ledger")
        val params = mutableMapOf<String, String>()
        if (accountId > 0) params["account_id"] = accountId.toString()
        if (entryId > 0) params["entry_id"] = entryId.toString()
        if (accountType.isNotBlank()) params["type"] = accountType
        val path = if (params.isEmpty()) "admin/accounting_ledger.php" else api.query("admin/accounting_ledger.php", params)
        api.request(path) { result ->
            onResult(result, "Could not load accounting ledger") { response ->
                val data = response.data()
                val form = screen("Accounting ledger", "Accounts, ledgers and journal entries")
                form.addView(heroBanner(
                    title = "Ledger explorer",
                    message = if (accountId > 0) "Inspect account movements and drill into journal entries." else "Find an account to inspect detailed ledger activity.",
                    eyebrow = "Journal analysis",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_receipt,
                    compact = true
                ))
                val accounts = data.optJSONArray("accounts") ?: JSONArray()
                form.addView(sectionTitle("Find account activity"))
                val typeChoices = accountTypeOptions()
                val typePicker = dropdownInput("Account type", typeChoices, accountType)
                form.addView(typePicker)
                form.addView(secondaryButton("Apply type filter").apply {
                    setOnClickListener {
                        childScreen()
                        loadAccountingLedger(0, 0, typePicker.tag?.toString().orEmpty())
                    }
                })
                val accountChoices = accountOptions(accounts, includeInactive = true)
                if (accountChoices.isEmpty()) {
                    form.addView(statusBanner(
                        "No accounts available",
                        "The server returned no ledger accounts for the current filter.",
                        toneAmber,
                        R.drawable.ic_warning_triangle,
                        false
                    ))
                }
                val accountPicker = dropdownInput(
                    "Account",
                    accountChoices,
                    accountId.takeIf { it > 0 }?.toString() ?: accountChoices.firstOrNull()?.first.orEmpty()
                )
                form.addView(accountPicker)
                form.addView(secondaryButton("View account ledger").apply {
                    setOnClickListener {
                        val selectedId = accountPicker.tag?.toString()?.toIntOrNull() ?: 0
                        if (selectedId == 0) {
                            toast("Choose an account to view its ledger.")
                        } else {
                            childScreen()
                            loadAccountingLedger(selectedId, 0, typePicker.tag?.toString().orEmpty())
                        }
                    }
                })
                val selectedAccount = data.optJSONObject("selected_account")
                if (selectedAccount != null) {
                    form.addView(summaryCardGrid(listOf(
                        summaryCard("Account", selectedAccount.optString("name"), R.drawable.ic_receipt, toneBlue),
                        summaryCard("Code", selectedAccount.optString("code"), R.drawable.ic_circle_check, toneTeal),
                        summaryCard(
                            "Type",
                            formatAccountType(selectedAccount.optString("account_type")),
                            R.drawable.ic_wallet,
                            toneBlue
                        ),
                        summaryCard(
                            "Status",
                            if (selectedAccount.optBoolean("is_active", true)) "Active" else "Inactive",
                            R.drawable.ic_circle_alert,
                            if (selectedAccount.optBoolean("is_active", true)) toneTeal else toneAmber
                        )
                    )))
                    form.addView(sectionTitle("Ledger: ${selectedAccount.optString("name")}"))
                    addRecordList(form, "Movements", data.optJSONArray("account_ledger"), listOf("entry_no", "line_memo"))
                }
                val journalEntries = data.optJSONArray("journal_entries") ?: JSONArray()
                form.addView(sectionTitle("Recent journal entries"))
                if (journalEntries.length() == 0) form.addView(empty("No journal entries found."))
                for (index in 0 until journalEntries.length()) {
                    val entry = journalEntries.optJSONObject(index) ?: continue
                    val row = card("${entry.optString("entry_no")} • ${entry.optString("entry_date")}", "${entry.optString("memo")} • ${entry.optString("status")}")
                    row.isClickable = true
                    row.isFocusable = true
                    val id = entry.optInt("id")
                    row.setOnClickListener { childScreen(); loadAccountingLedger(accountId, id, accountType) }
                    form.addView(row)
                }
                val selectedEntry = data.optJSONObject("selected_entry")
                if (selectedEntry != null) {
                    form.addView(sectionTitle("Entry detail"))
                    form.addView(summaryCardGrid(listOf(
                        summaryCard("Entry no", selectedEntry.optString("entry_no"), R.drawable.ic_receipt, toneBlue),
                        summaryCard("Status", selectedEntry.optString("status"), R.drawable.ic_circle_check, toneForStatus(selectedEntry.optString("status"), toneBlue)),
                        summaryCard("Date", selectedEntry.optString("entry_date"), R.drawable.ic_circle_clock, toneBlue),
                        summaryCard(
                            "Reference",
                            listOfNotNull(
                                selectedEntry.optString("reference_type"),
                                selectedEntry.opt("reference_id")?.toString().orEmpty().takeIf(String::isNotBlank)
                            ).filter(String::isNotBlank).joinToString(" #").ifBlank { "Manual" },
                            R.drawable.ic_wallet,
                            toneTeal
                        )
                    )))
                    selectedEntry.optString("memo").takeIf(String::isNotBlank)?.let { memoText ->
                        form.addView(statusBanner("Memo", memoText, toneBlue, R.drawable.ic_circle_alert, false))
                    }
                    addRecordList(form, "Lines", selectedEntry.optJSONArray("lines"), listOf("account_name", "line_memo"))
                    if (selectedEntry.optString("status") == "posted") {
                        val reverse = secondaryButton("Reverse this entry")
                        val id = selectedEntry.optInt("id")
                        reverse.setOnClickListener {
                            postAction(
                                "admin/accounting_ledger.php",
                                JSONObject().put("action", "reverse_entry").put("entry_id", id).put("reversal_date", today())
                            ) { childScreen(); loadAccountingLedger(accountId, 0, accountType) }
                        }
                        form.addView(reverse)
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Accounting budget
    // ---------------------------------------------------------------------

    private fun showAccountingBudget() {
        childScreen()
        showLoading("Loading accounting budget")
        api.request("admin/accounting_budget.php") { result ->
            onResult(result, "Could not load accounting budget") { response ->
                val data = response.data()
                val form = screen("Accounting budget", "Budget year: ${data.optString("budget_year")}")
                val accounts = data.optJSONArray("budget_accounts") ?: JSONArray()
                val accountChoices = accountOptions(accounts)
                val budgetRows = data.optJSONArray("budget_vs_actual") ?: JSONArray()
                val totalBudget = (0 until budgetRows.length()).sumOf { budgetRows.optJSONObject(it)?.optDouble("budget_total") ?: 0.0 }
                val totalActual = (0 until budgetRows.length()).sumOf { budgetRows.optJSONObject(it)?.optDouble("actual_total") ?: 0.0 }
                val totalVariance = (0 until budgetRows.length()).sumOf { budgetRows.optJSONObject(it)?.optDouble("variance_total") ?: 0.0 }
                form.addView(heroBanner(
                    title = "Budget performance",
                    message = "${budgetRows.length()} account budget(s) for ${data.optString("budget_year")}",
                    eyebrow = "Finance planning",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_wallet,
                    compact = true
                ))
                if (budgetRows.length() > 0) {
                    form.addView(summaryCardGrid(listOf(
                        summaryCard("Budget total", money(totalBudget), R.drawable.ic_wallet, toneBlue),
                        summaryCard("Actual total", money(totalActual), R.drawable.ic_receipt, toneTeal),
                        summaryCard(
                            "Variance",
                            money(totalVariance),
                            R.drawable.ic_warning_triangle,
                            if (kotlin.math.abs(totalVariance) > 0.009) toneAmber else toneTeal
                        )
                    )))
                }
                form.addView(sectionTitle("Budget performance"))
                renderBudgetVsActual(form, budgetRows)
                form.addView(sectionTitle("Set a yearly budget"))
                if (accountChoices.isEmpty()) {
                    form.addView(empty("No active accounts are available for budgeting yet."))
                } else {
                    val accountId = dropdownInput(
                        "Budget account",
                        accountChoices,
                        accountChoices.firstOrNull()?.first.orEmpty()
                    )
                    val year = input("Financial year (e.g. 2026)").apply { setText(data.optString("budget_year")) }
                    val yearlyBudget = input("Yearly budget amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                    val save = actionButton("Save budget")
                    listOf(accountId, year, yearlyBudget).forEach(form::addView)
                    form.addView(save)
                    save.setOnClickListener {
                        val selectedAccountId = accountId.tag?.toString()?.toIntOrNull() ?: 0
                        if (selectedAccountId == 0 || yearlyBudget.text.isBlank()) {
                            toast("Choose a budget account and enter the yearly budget.")
                            return@setOnClickListener
                        }
                        setLoading(save, true, "Save budget")
                        api.request(
                            "admin/accounting_budget.php", "POST",
                            JSONObject().put("action", "save_budget")
                                .put("budget_account_id", selectedAccountId)
                                .put("financial_year", year.text.toString().trim())
                                .put("budget_mode", "yearly")
                                .put("eliminate_decimals", false)
                                .put("yearly_budget", yearlyBudget.text.toString().toDoubleOrNull() ?: 0.0)
                        ) { r ->
                            runOnUiThread {
                                setLoading(save, false, "Save budget")
                                r.onSuccess { toast(it.optString("message", "Budget saved.")); showAccountingBudget() }.onFailure(::handleError)
                            }
                        }
                    }
                }
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Accounting transfers
    // ---------------------------------------------------------------------

    private fun showAccountingTransfers() {
        childScreen()
        showLoading("Loading accounting transfers")
        api.request("admin/accounting_transfers.php") { result ->
            onResult(result, "Could not load accounting transfers") { response ->
                val data = response.data()
                val form = screen("Accounting transfers", "Move funds between accounts")
                val accounts = data.optJSONArray("accounts") ?: JSONArray()
                val accountChoices = accountOptions(accounts)
                form.addView(heroBanner(
                    title = "Account transfers",
                    message = "Move funds between active accounts without leaving the mobile workspace.",
                    eyebrow = "Treasury",
                    tone = toneTeal,
                    iconRes = R.drawable.ic_wallet,
                    compact = true
                ))
                form.addView(sectionTitle("New transfer"))
                if (accountChoices.size < 2) {
                    form.addView(empty("At least two active accounts are required before you can post a transfer."))
                } else {
                    val transferDate = datePickerInput("Transfer date", today())
                    val fromAccountId = dropdownInput(
                        "From account",
                        accountChoices,
                        accountChoices.firstOrNull()?.first.orEmpty()
                    )
                    val toAccountId = dropdownInput(
                        "To account",
                        accountChoices,
                        accountChoices.getOrNull(1)?.first ?: accountChoices.firstOrNull()?.first.orEmpty()
                    )
                    val amount = input("Amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                    val memo = input("Memo (optional)")
                    val post = actionButton("Post transfer")
                    listOf(transferDate, fromAccountId, toAccountId, amount, memo).forEach(form::addView)
                    form.addView(post)
                    post.setOnClickListener {
                        val fromId = fromAccountId.tag?.toString()?.toIntOrNull() ?: 0
                        val toId = toAccountId.tag?.toString()?.toIntOrNull() ?: 0
                        if (fromId == 0 || toId == 0 || fromId == toId || amount.text.isBlank()) {
                            toast("Choose two different accounts and enter an amount.")
                            return@setOnClickListener
                        }
                        setLoading(post, true, "Post transfer")
                        api.request(
                            "admin/accounting_transfers.php", "POST",
                            JSONObject().put("action", "post_transfer")
                                .put("transfer_date", transferDate.text.toString().trim())
                                .put("from_account_id", fromId)
                                .put("to_account_id", toId)
                                .put("amount", amount.text.toString().toDoubleOrNull() ?: 0.0)
                                .put("memo", memo.text.toString().trim())
                        ) { r ->
                            runOnUiThread {
                                setLoading(post, false, "Post transfer")
                                r.onSuccess { toast(it.optString("message", "Transfer posted.")); showAccountingTransfers() }.onFailure(::handleError)
                            }
                        }
                    }
                }
                form.addView(sectionTitle("Transfer history"))
                addRecordList(form, "Recent transfers", data.optJSONArray("transfers"), listOf("memo"))
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Customer management (create/edit/status/meters) — distinct from the
    // read-only "Customers" search screen used by support/finance staff.
    // ---------------------------------------------------------------------

    private fun showCustomerManagement() {
        childScreen()
        loadCustomerManagement("")
    }

    private fun loadCustomerManagement(query: String) {
        showLoading("Loading customers")
        api.request(api.query("admin/users.php", mapOf("customer_search" to query, "limit" to "100"))) { result ->
            onResult(result, "Could not load customers") { response ->
                val data = response.data()
                val registrationFee = data.optDouble("registration_fee")
                val form = screen("Customer management", "Create, edit and manage customer accounts")
                val users = data.optJSONArray("users") ?: JSONArray()
                val (searchBox, search) = searchableIdentifierField("Search by name, account or meter", query) { item ->
                    val value = item.optString("selection_value").ifBlank { item.optString("account_number") }
                    childScreen(); loadCustomerManagement(value)
                }
                val searchBtn = actionButton("Search")
                form.addView(heroBanner(
                    title = "Customer management",
                    message = "Create, search and edit customer records from a grouped operations workspace.",
                    eyebrow = "Operations",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_group,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Customers", users.length().toString(), R.drawable.ic_group, toneBlue),
                    summaryCard("Registration fee", money(registrationFee), R.drawable.ic_wallet, toneTeal)
                )))
                form.addView(sectionPanel(
                    title = "Search and create",
                    description = "Use the same search-first workflow as the web customers page, or create a new account directly."
                ) {
                    addView(searchBox)
                    addView(searchBtn)
                })
                searchBtn.setOnClickListener { childScreen(); loadCustomerManagement(search.text.toString().trim()) }
                val create = actionButton("New customer")
                create.setOnClickListener { showCustomerCreateForm(registrationFee, data) }
                form.addView(sectionPanel(
                    title = "Customers (${users.length()})",
                    description = "Open a customer record to edit details, meters and account status."
                ) {
                    addView(create)
                    if (users.length() == 0) addView(empty("No customers found."))
                    for (index in 0 until users.length()) {
                        val user = users.optJSONObject(index) ?: continue
                        val row = card(
                            "${user.optString("full_name")} • ${user.optString("account_number")}",
                            "${user.optString("status")} • ${user.optString("phone_number")}\nMeters: ${user.optString("meter_numbers")}",
                            toneForStatus(user.optString("status"), toneBlue)
                        )
                        row.isClickable = true
                        row.isFocusable = true
                        val id = user.optInt("id")
                        row.setOnClickListener { showCustomerEditForm(id, registrationFee) }
                        addView(row)
                    }
                })
                addBack(form)
                show(form)
            }
        }
    }

    private fun showCustomerCreateForm(registrationFee: Double, data: JSONObject) {
        backAction = { showCustomerManagement() }
        val formMetadata = data.optJSONObject("form_metadata") ?: JSONObject()
        val countryOptions = formMetaOptions(formMetadata, "country_code_options", listOf("254" to "Kenya (+254)"))
        val connectionOptions = formMetaOptions(
            formMetadata,
            "connection_type_options",
            listOf("domestic" to "Domestic", "commercial" to "Commercial", "industrial" to "Industrial")
        )
        val firstName = input("First name")
        val middleName = input("Middle name (optional)")
        val lastName = input("Last name")
        val phoneCode = dropdownInput("Phone country code", countryOptions, formMetadata.optString("default_country_code").ifBlank { "254" })
        val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE)
        val email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val idNumber = input("ID number")
        val address = input("Address")
        val taxPin = input("Tax PIN (optional)")
        val connectionType = dropdownInput("Connection type", connectionOptions, "domestic")
        val unitRate = input("Custom unit rate (optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val password = input("Temporary password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val alreadyPaid = CheckBox(this).apply {
            text = getString(R.string.registration_fee_already_paid, money(registrationFee))
            setTextColor(textPrimary)
        }
        val paidAmount = input("Amount already paid (optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val mpesaCode = input("M-Pesa receipt code (optional)")
        val sendStk = CheckBox(this).apply {
            text = getString(R.string.registration_fee_stk_push)
            setTextColor(textPrimary)
        }
        val create = actionButton("Create customer")
        val form = screen("New customer", "Registration fee: ${money(registrationFee)}")
        form.addView(heroBanner(
            title = "Create customer",
            message = "Register a new account and handle the registration fee from the same workflow.",
            eyebrow = "Operations",
            tone = toneBlue,
            iconRes = R.drawable.ic_person,
            compact = true
        ))
        form.addView(summaryCardGrid(listOf(
            summaryCard("Registration fee", money(registrationFee), R.drawable.ic_wallet, toneTeal),
            summaryCard("Connection", "New account", R.drawable.ic_meter, toneBlue)
        )))
        form.addView(sectionPanel(
            title = "Identity and contact",
            description = "Capture the core customer record first, similar to the web customer registration flow."
        ) {
            listOf(firstName, middleName, lastName, phoneCode, phoneLocal, email, idNumber, address, taxPin).forEach(this::addView)
        })
        form.addView(sectionPanel(
            title = "Service setup",
            description = "Connection defaults and temporary access credentials for the new account."
        ) {
            listOf(connectionType, unitRate, password).forEach(this::addView)
        })
        form.addView(sectionPanel(
            title = "Registration fee",
            description = "Record existing payment or trigger a registration STK push when needed."
        ) {
            listOf(alreadyPaid, paidAmount, mpesaCode, sendStk, create).forEach(this::addView)
        })
        addBack(form)
        create.setOnClickListener {
            if (firstName.text.isBlank() || lastName.text.isBlank() || phoneLocal.text.isBlank() ||
                email.text.isBlank() || idNumber.text.isBlank() || address.text.isBlank() || password.text.isBlank()
            ) {
                toast("Please fill in all required fields.")
                return@setOnClickListener
            }
            val body = JSONObject()
                .put("form_type", "create_user")
                .put("first_name", firstName.text.toString().trim())
                .put("middle_name", middleName.text.toString().trim())
                .put("last_name", lastName.text.toString().trim())
                .put("phone_country_code", phoneCode.tag?.toString().orEmpty())
                .put("phone_number_local", phoneLocal.text.toString().trim())
                .put("email", email.text.toString().trim())
                .put("id_number", idNumber.text.toString().trim())
                .put("address", address.text.toString().trim())
                .put("tax_pin", taxPin.text.toString().trim())
                .put("connection_type", connectionType.tag?.toString().orEmpty())
                .put("unit_rate", unitRate.text.toString().trim())
                .put("password", password.text.toString())
                .put("registration_already_paid", alreadyPaid.isChecked)
                .put("registration_paid_amount", paidAmount.text.toString().trim())
                .put("registration_mpesa_code", mpesaCode.text.toString().trim())
                .put("send_stk", sendStk.isChecked)
            setLoading(create, true, "Create customer")
            api.request("admin/users.php", "POST", body) { r ->
                runOnUiThread {
                    setLoading(create, false, "Create customer")
                    r.onSuccess { toast(it.optString("message", "Customer created.")); showCustomerManagement() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showCustomerEditForm(userId: Int, registrationFee: Double) {
        backAction = { showCustomerManagement() }
        showLoading("Loading customer")
        api.request(api.query("admin/users.php", mapOf("edit_id" to userId.toString()))) { result ->
            onResult(result, "Could not load customer") { response ->
                val data = response.data()
                val formMetadata = data.optJSONObject("form_metadata") ?: JSONObject()
                val countryOptions = formMetaOptions(formMetadata, "country_code_options", listOf("254" to "Kenya (+254)"))
                val connectionOptions = formMetaOptions(
                    formMetadata,
                    "connection_type_options",
                    listOf("domestic" to "Domestic", "commercial" to "Commercial", "industrial" to "Industrial")
                )
                val user = data.optJSONObject("edit_user") ?: JSONObject()
                val nameParts = user.optString("full_name").trim().split(Regex("\\s+")).filter(String::isNotBlank)
                val (selectedPhoneCode, localPhone) = splitPhoneNumberForForm(
                    user.optString("phone_number"),
                    countryOptions,
                    formMetadata.optString("default_country_code").ifBlank { "254" }
                )
                val firstName = input("First name").apply { setText(nameParts.getOrNull(0) ?: "") }
                val middleName = input("Middle name (optional)").apply {
                    setText(if (nameParts.size > 2) nameParts.subList(1, nameParts.size - 1).joinToString(" ") else "")
                }
                val lastName = input("Last name").apply { setText(if (nameParts.size > 1) nameParts.last() else "") }
                val phoneCode = dropdownInput("Phone country code", countryOptions, selectedPhoneCode)
                val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE).apply { setText(localPhone) }
                val email = input("Email").apply { setText(user.optString("email")) }
                val idNumber = input("ID number").apply { setText(user.optString("id_number")) }
                val address = input("Address").apply { setText(user.optString("address")) }
                val taxPin = input("Tax PIN (optional)").apply { setText(user.optString("tax_pin")) }
                val meterNumber = input("Primary meter number").apply { setText(user.optString("meter_number")) }
                val connectionType = dropdownInput("Connection type", connectionOptions, user.optString("connection_type").ifBlank { "domestic" })
                val unitRate = input("Custom unit rate (optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                    if (!user.isNull("unit_rate")) setText(user.optString("unit_rate"))
                }
                val locationLabel = input("Location label (optional)").apply { setText(user.optString("location_label")) }
                val latitude = input("Latitude (optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED).apply {
                    if (!user.isNull("latitude")) setText(user.optString("latitude"))
                }
                val longitude = input("Longitude (optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED).apply {
                    if (!user.isNull("longitude")) setText(user.optString("longitude"))
                }
                val newPassword = input("New password (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                val save = actionButton("Save customer")
                val form = screen(user.optString("full_name").takeIf(String::isNotBlank) ?: "Edit customer", user.optString("account_number"))
                form.addView(heroBanner(
                    title = user.optString("full_name").takeIf(String::isNotBlank) ?: "Edit customer",
                    message = listOf(user.optString("account_number"), user.optString("status")).filter(String::isNotBlank).joinToString(" • "),
                    eyebrow = "Customer account",
                    tone = toneForStatus(user.optString("status"), toneBlue),
                    iconRes = R.drawable.ic_person,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Registration fee", money(registrationFee), R.drawable.ic_wallet, toneTeal),
                    summaryCard("Meters", (data.optJSONArray("edit_user_meters") ?: JSONArray()).length().toString(), R.drawable.ic_meter, toneBlue),
                    summaryCard("Status", user.optString("status").ifBlank { "Unknown" }, R.drawable.ic_circle_check, toneForStatus(user.optString("status"), toneBlue))
                )))
                form.addView(sectionPanel(
                    title = "Identity and contact",
                    description = "Edit personal and communication details for this account."
                ) {
                    listOf(firstName, middleName, lastName, phoneCode, phoneLocal, email, idNumber, address, taxPin).forEach(this::addView)
                })
                form.addView(sectionPanel(
                    title = "Service settings",
                    description = "Primary meter, connection type, pricing override, and location metadata."
                ) {
                    listOf(meterNumber, connectionType, unitRate, locationLabel, latitude, longitude, newPassword, save).forEach(this::addView)
                })
                addBack(form)
                form.addView(sectionPanel(
                    title = "Account actions",
                    description = "Status changes and additional meter operations for this customer."
                ) {
                    addView(buttonRow(
                        "Activate" to { postAction("admin/users.php", JSONObject().put("form_type", "update_status").put("user_id", userId).put("new_status", "active")) { showCustomerManagement() } },
                        "Suspend" to { postAction("admin/users.php", JSONObject().put("form_type", "update_status").put("user_id", userId).put("new_status", "suspended")) { showCustomerManagement() } }
                    ))
                })
                val extraMeterNumber = input("Additional meter number")
                val extraMeterLabel = input("Meter label (optional)")
                val addMeter = secondaryButton("Add additional meter")
                form.addView(sectionPanel(
                    title = "Add another meter",
                    description = "Attach an extra service point to this account."
                ) {
                    addView(extraMeterNumber)
                    addView(extraMeterLabel)
                    addView(addMeter)
                })
                addMeter.setOnClickListener {
                    if (extraMeterNumber.text.isBlank()) {
                        toast("Enter a meter number")
                        return@setOnClickListener
                    }
                    postAction(
                        "admin/users.php",
                        JSONObject().put("form_type", "add_client_meter").put("user_id", userId)
                            .put("additional_meter_number", extraMeterNumber.text.toString().trim())
                            .put("additional_meter_label", extraMeterLabel.text.toString().trim())
                    ) { showCustomerEditForm(userId, registrationFee) }
                }
                val meters = data.optJSONArray("edit_user_meters") ?: JSONArray()
                form.addView(sectionPanel(
                    title = "Meters on this account",
                    description = "Registered service points and their replacement history."
                ) {
                    if (meters.length() == 0) addView(empty("No meters recorded for this customer."))
                    for (index in 0 until meters.length()) {
                        val meter = meters.optJSONObject(index) ?: continue
                        val meterStatus = meter.optString("status", "active")
                        addView(card(
                            meter.optString("meter_number"),
                            listOf(
                                meter.optString("meter_label"),
                                meterStatus,
                                if (meter.optBoolean("is_primary")) "Primary meter" else ""
                            ).filter(String::isNotBlank).joinToString(" • "),
                            if (meterStatus.equals("active", ignoreCase = true)) toneBlue else toneNeutral
                        ))
                        if (meterStatus.equals("active", ignoreCase = true)) {
                            val replace = secondaryButton("Replace this meter")
                            replace.setOnClickListener { showMeterReplacementForm(userId, registrationFee, meter) }
                            addView(replace)
                        }
                    }
                    addRecordList(
                        this,
                        "Meter replacement history",
                        data.optJSONArray("edit_user_meter_replacements"),
                        listOf("replacement_meter_number", "meter_number", "old_meter_number")
                    )
                })
                if (user.optString("status") != "active") {
                    val resendStk = secondaryButton("Resend registration STK push")
                    resendStk.setOnClickListener {
                        postAction("admin/users.php", JSONObject().put("form_type", "resend_registration_stk").put("user_id", userId)) {
                            showCustomerEditForm(userId, registrationFee)
                        }
                    }
                    form.addView(sectionPanel(
                        title = "Registration follow-up",
                        description = "Retry registration payment collection for inactive accounts."
                    ) {
                        addView(resendStk)
                    })
                }
                val delete = destructiveButton("Delete customer")
                delete.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete customer")
                        .setMessage("This will permanently delete this customer account.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            postAction("admin/users.php", JSONObject().put("form_type", "delete_user").put("user_id", userId)) { showCustomerManagement() }
                        }
                        .show()
                }
                form.addView(sectionPanel(
                    title = "Danger zone",
                    description = "Permanent customer removal should only be used when you are certain."
                ) {
                    addView(delete)
                })
                save.setOnClickListener {
                    val body = JSONObject()
                        .put("form_type", "edit_user_save")
                        .put("user_id", userId)
                        .put("first_name", firstName.text.toString().trim())
                        .put("middle_name", middleName.text.toString().trim())
                        .put("last_name", lastName.text.toString().trim())
                        .put("phone_country_code", phoneCode.tag?.toString().orEmpty())
                        .put("phone_number_local", phoneLocal.text.toString().trim())
                        .put("email", email.text.toString().trim())
                        .put("id_number", idNumber.text.toString().trim())
                        .put("address", address.text.toString().trim())
                        .put("tax_pin", taxPin.text.toString().trim())
                        .put("meter_number", meterNumber.text.toString().trim())
                        .put("connection_type", connectionType.tag?.toString().orEmpty())
                        .put("unit_rate", unitRate.text.toString().trim())
                        .put("location_label", locationLabel.text.toString().trim())
                        .put("latitude", latitude.text.toString().trim())
                        .put("longitude", longitude.text.toString().trim())
                    if (newPassword.text.isNotBlank()) body.put("password", newPassword.text.toString())
                    setLoading(save, true, "Save customer")
                    api.request("admin/users.php", "POST", body) { r ->
                        runOnUiThread {
                            setLoading(save, false, "Save customer")
                            r.onSuccess { toast(it.optString("message", "Customer updated.")); showCustomerManagement() }.onFailure(::handleError)
                        }
                    }
                }
                show(form)
            }
        }
    }

    private fun showMeterReplacementForm(userId: Int, registrationFee: Double, meter: JSONObject) {
        val meterId = meter.optInt("id")
        val meterNumberLabel = listOf(meter.optString("meter_number"), meter.optString("meter_label"))
            .filter(String::isNotBlank).joinToString(" • ")
        backAction = { showCustomerEditForm(userId, registrationFee) }
        val replacementNumber = input("Replacement meter number")
        val replacementLabel = input("Replacement meter label (optional)")
        val oldFinalReading = input("Old meter final reading", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val newOpeningReading = input("New meter opening reading", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val reason = multilineInput("Replacement reason")
        val submit = actionButton("Replace meter")
        val form = screen("Replace meter", meterNumberLabel)
        form.addView(card("Current meter", meterNumberLabel, toneAmber))
        listOf(replacementNumber, replacementLabel, oldFinalReading, newOpeningReading, reason, submit).forEach(form::addView)
        addBack(form)
        submit.setOnClickListener {
            if (replacementNumber.text.isBlank() || oldFinalReading.text.isBlank() ||
                newOpeningReading.text.isBlank() || reason.text.isBlank()
            ) {
                toast("Enter the replacement meter number, both readings and a reason.")
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Confirm meter replacement")
                .setMessage(
                    "Replace meter ${meter.optString("meter_number")} with " +
                        "${replacementNumber.text}? This action cannot be undone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ ->
                    setLoading(submit, true, "Replace meter")
                    val body = JSONObject()
                        .put("form_type", "replace_client_meter")
                        .put("user_id", userId)
                        .put("old_meter_id", meterId)
                        .put("replacement_meter_number", replacementNumber.text.toString().trim())
                        .put("replacement_meter_label", replacementLabel.text.toString().trim())
                        .put("old_final_reading", oldFinalReading.text.toString().trim())
                        .put("new_opening_reading", newOpeningReading.text.toString().trim())
                        .put("replacement_reason", reason.text.toString().trim())
                    api.request("admin/users.php", "POST", body) { r ->
                        runOnUiThread {
                            setLoading(submit, false, "Replace meter")
                            r.onSuccess {
                                toast(it.optString("message", "Meter replaced."))
                                showCustomerEditForm(userId, registrationFee)
                            }.onFailure(::handleError)
                        }
                    }
                }
                .show()
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Staff users
    // ---------------------------------------------------------------------

    private fun showStaffUsers() {
        childScreen()
        showLoading("Loading staff users")
        api.request("admin/staff_users.php") { result ->
            onResult(result, "Could not load staff users") { response ->
                val users = response.data().optJSONArray("users") ?: JSONArray()
                val form = screen("Staff users", "${users.length()} staff account(s)")
                val create = actionButton("New staff account")
                create.setOnClickListener { showStaffEditor(null, response.data()) }
                form.addView(create)
                if (users.length() == 0) form.addView(empty("No staff accounts found."))
                for (index in 0 until users.length()) {
                    val user = users.optJSONObject(index) ?: continue
                    val row = card(
                        "${user.optString("full_name")} • ${user.optString("role")}",
                        "${user.optString("account_number")} • ${user.optString("status")}\n${user.optString("phone_number")}"
                    )
                    row.isClickable = true
                    row.isFocusable = true
                    row.setOnClickListener { showStaffEditor(user, response.data()) }
                    form.addView(row)
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showStaffEditor(user: JSONObject?, data: JSONObject? = null) {
        backAction = ::showStaffUsers
        val isNew = user == null
        val formMetadata = data?.optJSONObject("form_metadata") ?: JSONObject()
        val countryOptions = formMetaOptions(formMetadata, "country_code_options", listOf("254" to "Kenya (+254)"))
        val roleOptions = formMetaOptions(
            formMetadata,
            "role_options",
            listOf("admin" to "Admin", "reader" to "Reader", "finance" to "Finance", "support" to "Support")
        )
        val statusOptions = formMetaOptions(
            formMetadata,
            "status_options",
            listOf("active" to "Active", "inactive" to "Inactive", "suspended" to "Suspended")
        )
        val nameParts = user?.optString("full_name")?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: emptyList()
        val (selectedPhoneCode, localPhone) = splitPhoneNumberForForm(
            user?.optString("phone_number") ?: "",
            countryOptions,
            formMetadata.optString("default_country_code").ifBlank { "254" }
        )
        val firstName = input("First name").apply { setText(nameParts.getOrNull(0) ?: "") }
        val middleName = input("Middle name (optional)").apply {
            setText(if (nameParts.size > 2) nameParts.subList(1, nameParts.size - 1).joinToString(" ") else "")
        }
        val lastName = input("Last name").apply { setText(if (nameParts.size > 1) nameParts.last() else "") }
        val username = input("Username").apply { setText(user?.optString("username") ?: "") }
        val phoneCode = dropdownInput("Phone country code", countryOptions, selectedPhoneCode)
        val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE).apply { setText(localPhone) }
        val idNumber = input("ID number").apply { setText(user?.optString("id_number") ?: "") }
        val role = dropdownInput("Role", roleOptions, user?.optString("role")?.takeIf(String::isNotBlank) ?: "reader")
        val status = dropdownInput("Status", statusOptions, user?.optString("status")?.takeIf(String::isNotBlank) ?: "active")
        val password = input("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val confirmPassword = input("Confirm password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val save = actionButton(if (isNew) "Create staff account" else "Save staff account")
        val form = screen(if (isNew) "New staff account" else "Edit staff account", user?.optString("account_number") ?: "")
        val fields = if (isNew) {
            listOf(firstName, middleName, lastName, username, phoneCode, phoneLocal, idNumber, role, password, confirmPassword, save)
        } else {
            listOf(firstName, middleName, lastName, username, phoneCode, phoneLocal, idNumber, role, status, save)
        }
        fields.forEach(form::addView)
        addBack(form)
        if (user != null) {
            val userId = user.optInt("id")
            val resetPassword = secondaryButton("Reset password")
            resetPassword.setOnClickListener { showStaffPasswordReset(userId) }
            form.addView(resetPassword)
            val delete = destructiveButton("Delete staff account")
            delete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete staff account")
                    .setMessage("This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        postAction("admin/staff_users.php", JSONObject().put("form_type", "delete_user").put("user_id", userId)) { showStaffUsers() }
                    }
                    .show()
            }
            form.addView(delete)
        }
        save.setOnClickListener {
            if (user == null) {
                if (firstName.text.isBlank() || lastName.text.isBlank() || username.text.isBlank() ||
                    phoneLocal.text.isBlank() || idNumber.text.isBlank() || password.text.isBlank()
                ) {
                    toast("Please fill in all required fields.")
                    return@setOnClickListener
                }
                val body = JSONObject()
                    .put("form_type", "create_staff")
                    .put("first_name", firstName.text.toString().trim())
                    .put("middle_name", middleName.text.toString().trim())
                    .put("last_name", lastName.text.toString().trim())
                    .put("username", username.text.toString().trim())
                    .put("phone_country_code", phoneCode.tag?.toString().orEmpty())
                    .put("phone_number_local", phoneLocal.text.toString().trim())
                    .put("id_number", idNumber.text.toString().trim())
                    .put("role", role.tag?.toString().orEmpty())
                    .put("password", password.text.toString())
                    .put("confirm_password", confirmPassword.text.toString())
                setLoading(save, true, "Create staff account")
                api.request("admin/staff_users.php", "POST", body) { r ->
                    runOnUiThread {
                        setLoading(save, false, "Create staff account")
                        r.onSuccess { toast(it.optString("message", "Staff account created.")); showStaffUsers() }.onFailure(::handleError)
                    }
                }
            } else {
                val fullName = listOf(firstName.text.toString(), middleName.text.toString(), lastName.text.toString())
                    .joinToString(" ").replace(Regex("\\s+"), " ").trim()
                val body = JSONObject()
                    .put("form_type", "edit_staff")
                    .put("user_id", user.optInt("id"))
                    .put("full_name", fullName)
                    .put("username", username.text.toString().trim())
                    .put("phone_country_code", phoneCode.tag?.toString().orEmpty())
                    .put("phone_number_local", phoneLocal.text.toString().trim())
                    .put("id_number", idNumber.text.toString().trim())
                    .put("role", role.tag?.toString().orEmpty())
                    .put("status", status.tag?.toString().orEmpty())
                setLoading(save, true, "Save staff account")
                api.request("admin/staff_users.php", "POST", body) { r ->
                    runOnUiThread {
                        setLoading(save, false, "Save staff account")
                        r.onSuccess { toast(it.optString("message", "Staff account updated.")); showStaffUsers() }.onFailure(::handleError)
                    }
                }
            }
        }
        show(form)
    }

    private fun showStaffPasswordReset(userId: Int) {
        backAction = ::showStaffUsers
        val newPassword = input("New password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val confirmPassword = input("Confirm new password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val save = actionButton("Reset password")
        val form = screen("Reset staff password", "Staff user #$userId")
        listOf(newPassword, confirmPassword, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            if (newPassword.text.length < 8 || newPassword.text.toString() != confirmPassword.text.toString()) {
                toast("Password must be at least 8 characters and match confirmation.")
                return@setOnClickListener
            }
            setLoading(save, true, "Reset password")
            api.request(
                "admin/staff_users.php", "POST",
                JSONObject().put("form_type", "reset_password").put("user_id", userId)
                    .put("new_password", newPassword.text.toString()).put("confirm_password", confirmPassword.text.toString())
            ) { r ->
                runOnUiThread {
                    setLoading(save, false, "Reset password")
                    r.onSuccess { toast(it.optString("message", "Password reset.")); showStaffUsers() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Role permissions
    // ---------------------------------------------------------------------

    private fun showRolePermissions() {
        childScreen()
        showLoading("Loading role permissions")
        api.request("admin/role_permissions.php") { result ->
            onResult(result, "Could not load role permissions") { response ->
                val data = response.data()
                val roles = data.optJSONArray("roles") ?: JSONArray()
                val defs = data.optJSONObject("permission_defs") ?: JSONObject()
                val current = data.optJSONObject("current") ?: JSONObject()
                val permKeys = defs.keys().asSequence().toList()
                val form = screen("Role permissions", "Grant staff roles access to admin features")
                val checkboxes = mutableMapOf<String, MutableMap<String, CheckBox>>()
                for (roleIndex in 0 until roles.length()) {
                    val role = roles.optString(roleIndex)
                    form.addView(sectionTitle(role.replaceFirstChar { c -> c.uppercase() }))
                    val roleCurrent = current.optJSONObject(role) ?: JSONObject()
                    val roleBoxes = mutableMapOf<String, CheckBox>()
                    permKeys.forEach { perm ->
                        val def = defs.optJSONObject(perm) ?: JSONObject()
                        val box = CheckBox(this).apply {
                            text = def.optString("label", perm)
                            isChecked = roleCurrent.optBoolean(perm)
                            setTextColor(textPrimary)
                        }
                        roleBoxes[perm] = box
                        form.addView(box)
                    }
                    checkboxes[role] = roleBoxes
                }
                val save = actionButton("Save permissions")
                save.setOnClickListener {
                    val permissions = JSONObject()
                    checkboxes.forEach { (role, boxes) ->
                        val roleObj = JSONObject()
                        boxes.forEach { (perm, box) -> roleObj.put(perm, box.isChecked) }
                        permissions.put(role, roleObj)
                    }
                    setLoading(save, true, "Save permissions")
                    api.request("admin/role_permissions.php", "POST", JSONObject().put("permissions", permissions)) { r ->
                        runOnUiThread {
                            setLoading(save, false, "Save permissions")
                            r.onSuccess { toast(it.optString("message", "Permissions saved.")); showRolePermissions() }.onFailure(::handleError)
                        }
                    }
                }
                form.addView(save)
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Customer locations
    // ---------------------------------------------------------------------

    private fun showCustomerLocations() {
        childScreen()
        showLoading("Loading customer locations")
        api.request("admin/customer_locations.php") { result ->
            onResult(result, "Could not load customer locations") { response ->
                val pins = response.data().optJSONArray("pins") ?: JSONArray()
                val form = screen("Customer locations", "${pins.length()} pinned location(s)")
                if (pins.length() == 0) form.addView(empty("No customer GPS locations recorded."))
                for (index in 0 until pins.length()) {
                    val pin = pins.optJSONObject(index) ?: continue
                    form.addView(card(
                        "${pin.optString("full_name")} • ${pin.optString("account_number")}",
                        "${pin.optString("location_label")}\n${pin.optString("address")} • ${pin.optString("status")}"
                    ))
                    val lat = pin.optString("latitude")
                    val lng = pin.optString("longitude")
                    if (lat.isNotBlank() && lng.isNotBlank()) {
                        val openMap = secondaryButton("Open in Maps")
                        openMap.setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, "geo:$lat,$lng?q=$lat,$lng(${Uri.encode(pin.optString("full_name"))})".toUri()))
                            } catch (_: Exception) {
                                toast("No map app is available.")
                            }
                        }
                        form.addView(openMap)
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    private fun showSettings() {
        childScreen()
        showLoading("Loading settings")
        api.request("admin/settings.php") { result ->
            onResult(result, "Could not load settings") { response ->
                val data = response.data()
                val settings = data.optJSONObject("settings") ?: JSONObject()
                val form = screen("System settings", "Mobile API key: ${data.optString("mobile_api_key_masked")}")
                val rate = input("Rate per m3", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                    setText(settings.optString("rate_per_unit"))
                }
                val service = input("Service charge", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                    setText(settings.optString("service_charge"))
                }
                val companyName = input("Company name").apply { setText(settings.optString("company_name")) }
                val supportPhone = input("Support phone").apply { setText(settings.optString("support_phone")) }
                val supportEmail = input("Support email").apply { setText(settings.optString("support_email")) }
                val currencyCode = input("Currency code").apply { setText(settings.optString("currency_code").takeIf(String::isNotBlank) ?: "KES") }
                val registrationFee = input("Registration fee", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                    setText(settings.optString("registration_fee"))
                }
                val requireApiKey = CheckBox(this).apply {
                    text = getString(R.string.require_mobile_api_key)
                    isChecked = settings.optBoolean("mobile_api_key_required")
                    setTextColor(textPrimary)
                }
                val save = actionButton("Save settings")
                listOf(rate, service, companyName, supportPhone, supportEmail, currencyCode, registrationFee, requireApiKey, save).forEach(form::addView)
                addBack(form)
                save.setOnClickListener {
                    val body = JSONObject().put("action", "update_settings")
                        .put("rate_per_unit", rate.text.toString().toDoubleOrNull() ?: 0.0)
                        .put("service_charge", service.text.toString().toDoubleOrNull() ?: 0.0)
                        .put("company_name", companyName.text.toString().trim())
                        .put("support_phone", supportPhone.text.toString().trim())
                        .put("support_email", supportEmail.text.toString().trim())
                        .put("currency_code", currencyCode.text.toString().trim().uppercase())
                        .put("registration_fee", registrationFee.text.toString().toDoubleOrNull() ?: 0.0)
                        .put("mobile_api_key_required", requireApiKey.isChecked)
                    setLoading(save, true, "Save settings")
                    api.request("admin/settings.php", "POST", body) { r ->
                        runOnUiThread {
                            setLoading(save, false, "Save settings")
                            r.onSuccess { toast(it.optString("message", "Settings updated.")); showSettings() }.onFailure(::handleError)
                        }
                    }
                }
                form.addView(sectionTitle("Tariff plans"))
                val tariffPlans = data.optJSONArray("tariff_plans") ?: JSONArray()
                if (tariffPlans.length() == 0) form.addView(empty("No tariff plans configured."))
                for (index in 0 until tariffPlans.length()) {
                    val plan = tariffPlans.optJSONObject(index) ?: continue
                    val planId = plan.optInt("id")
                    val isActive = plan.optBoolean("is_active")
                    form.addView(card(plan.optString("name"), "${plan.optString("category")} • active=$isActive\nRate ${plan.optString("base_rate_per_unit")}"))
                    form.addView(buttonRow(
                        (if (isActive) "Deactivate" else "Activate") to {
                            postAction("admin/settings.php", JSONObject().put("action", "toggle_tariff_plan").put("tariff_plan_id", planId).put("is_active", !isActive)) { showSettings() }
                        },
                        "Delete" to {
                            postAction("admin/settings.php", JSONObject().put("action", "delete_tariff_plan").put("tariff_plan_id", planId)) { showSettings() }
                        }
                    ))
                }
                val newTariff = actionButton("New tariff plan")
                newTariff.setOnClickListener { showTariffPlanEditor() }
                form.addView(newTariff)
                show(form)
            }
        }
    }

    private fun showTariffPlanEditor() {
        backAction = ::showSettings
        val categoryOptions = listOf(
            "all" to "All connections",
            "domestic" to "Domestic",
            "commercial" to "Commercial",
            "industrial" to "Industrial"
        )
        val name = input("Tariff name")
        val category = dropdownInput("Tariff category", categoryOptions, "all")
        val effectiveFrom = datePickerInput("Effective from", today())
        val baseRate = input("Base rate per unit", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val serviceCharge = input("Service charge", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val vatRate = input("VAT rate %", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val active = CheckBox(this).apply { text = getString(R.string.active_label); isChecked = true; setTextColor(textPrimary) }
        val save = actionButton("Save tariff plan")
        val form = screen("New tariff plan", "")
        listOf(name, category, effectiveFrom, baseRate, serviceCharge, vatRate, active, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            if (name.text.isBlank() || baseRate.text.isBlank()) {
                toast("Enter a name and base rate.")
                return@setOnClickListener
            }
            setLoading(save, true, "Save tariff plan")
            api.request(
                "admin/settings.php", "POST",
                JSONObject().put("action", "save_tariff_plan").put("tariff_plan_id", 0)
                    .put("tariff_name", name.text.toString().trim())
                    .put("tariff_category", category.tag?.toString().orEmpty())
                    .put("effective_from", effectiveFrom.text.toString().trim())
                    .put("base_rate_per_unit", baseRate.text.toString().toDoubleOrNull() ?: 0.0)
                    .put("tariff_service_charge", serviceCharge.text.toString().toDoubleOrNull() ?: 0.0)
                    .put("tariff_vat_rate", vatRate.text.toString().toDoubleOrNull() ?: 0.0)
                    .put("tariff_is_active", active.isChecked)
            ) { r ->
                runOnUiThread {
                    setLoading(save, false, "Save tariff plan")
                    r.onSuccess { toast(it.optString("message", "Tariff plan saved.")); showSettings() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Terms & Conditions
    // ---------------------------------------------------------------------

    private var termsFormatOverride: String? = null

    private fun showTermsConditions() {
        childScreen()
        showLoading("Loading terms and conditions")
        api.request("admin/terms_conditions.php") { result ->
            onResult(result, "Could not load terms and conditions") { response ->
                val data = response.data()
                val termsText = data.optString("terms_conditions_content")
                val termsTemplate = data.optString("terms_conditions_template").ifBlank { termsText }
                val displayMetadata = data.optJSONObject("display_metadata") ?: JSONObject()
                val availableFormats = displayMetadata.optJSONArray("available_formats")?.let { options ->
                    (0 until options.length()).mapNotNull { index -> options.optString(index).takeIf(String::isNotBlank) }
                }?.ifEmpty { null } ?: listOf("sections", "text", "html")
                val selectedFormat = termsFormatOverride?.takeIf { it in availableFormats }
                    ?: displayMetadata.optString("preferred_format").takeIf { it in availableFormats }
                    ?: availableFormats.firstOrNull().orEmpty().ifBlank { "sections" }
                val sampleTemplates = data.optJSONObject("sample_templates") ?: JSONObject()
                val form = screen("Terms & Conditions", "Customer terms and conditions")
                if (availableFormats.size > 1) {
                    form.addView(sectionPanel(
                        title = "Display format",
                        description = "Preview the terms using the server's available presentation formats."
                    ) {
                        addView(buttonRow(*availableFormats.map { format ->
                            format.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } to {
                                termsFormatOverride = format
                                showTermsConditions()
                            }
                        }.toTypedArray()))
                    })
                }
                when (selectedFormat) {
                    "sections" -> {
                        val sections = data.optJSONArray("rendered_terms_sections")
                        if (sections != null && sections.length() > 0) {
                            for (index in 0 until sections.length()) {
                                val section = sections.optJSONObject(index) ?: continue
                                val heading = section.optString("title")
                                val text = section.optString("content")
                                if (heading.isBlank() && text.isBlank()) continue
                                form.addView(card(heading.ifBlank { "Terms & Conditions" }, text))
                            }
                        } else {
                            form.addView(empty("Terms and conditions have not been added yet."))
                        }
                    }
                    "text" -> {
                        val plainText = data.optString("rendered_terms_text").ifBlank { termsText }
                        if (plainText.isBlank()) {
                            form.addView(empty("Terms and conditions have not been added yet."))
                        } else {
                            form.addView(card("Terms & Conditions", plainText))
                        }
                    }
                    else -> {
                        val htmlContent = data.optString("rendered_terms").ifBlank { termsText }
                        if (htmlContent.isBlank()) {
                            form.addView(empty("Terms and conditions have not been added yet."))
                        } else {
                            val formattedTerms = TextView(this).apply {
                                text = Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_COMPACT)
                                textSize = 16f
                                setTextColor(textPrimary)
                                setPadding(dp(16), dp(16), dp(16), dp(16))
                                background = roundedDrawable(cardBackground, border, 14f)
                                setTextIsSelectable(true)
                            }
                            form.addView(formattedTerms, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(14) })
                        }
                    }
                }
                val edit = secondaryButton("Edit terms and conditions")
                form.addView(edit)
                addBack(form)
                edit.setOnClickListener {
                    val content = multilineInput("Write terms and conditions").apply {
                        setText(termsTemplate)
                        minLines = 8
                    }
                    val password = input(
                        "Confirm your password to save",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    )
                    val editor = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), 0)
                        if (sampleTemplates.length() > 0) {
                            addView(secondaryButton("Load sample template").apply {
                                setOnClickListener {
                                    val sampleKeys = mutableListOf<String>()
                                    val sampleLabels = mutableListOf<String>()
                                    val iterator = sampleTemplates.keys()
                                    while (iterator.hasNext()) {
                                        val key = iterator.next()
                                        val sample = sampleTemplates.optJSONObject(key) ?: continue
                                        sampleKeys += key
                                        sampleLabels += listOfNotNull(
                                            sample.optString("label").ifBlank { key.replace('_', ' ') },
                                            sample.optString("description").takeIf(String::isNotBlank)
                                        ).filter(String::isNotBlank).joinToString(" • ")
                                    }
                                    if (sampleKeys.isEmpty()) return@setOnClickListener
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Choose template sample")
                                        .setItems(sampleLabels.toTypedArray()) { _, which ->
                                            val sample = sampleTemplates.optJSONObject(sampleKeys[which]) ?: return@setItems
                                            content.setText(sample.optString("content"))
                                            content.setSelection(content.text.length)
                                        }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                }
                            })
                        }
                        addView(content)
                        addView(password)
                    }
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("Edit terms and conditions")
                        .setView(editor)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (password.text.isBlank()) {
                                toast("Enter your password to confirm.")
                            } else if (content.text.isBlank()) {
                                toast("Enter the terms and conditions.")
                            } else {
                                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                saveButton.isEnabled = false
                                api.request(
                                    "admin/terms_conditions.php", "POST",
                                    JSONObject().put("current_password", password.text.toString())
                                        .put("terms_conditions_template", content.text.toString())
                                ) { saveResult ->
                                    runOnUiThread {
                                        saveButton.isEnabled = true
                                        saveResult.onSuccess {
                                            toast(it.optString("message", "Terms updated."))
                                            dialog.dismiss()
                                            showTermsConditions()
                                        }.onFailure(::handleError)
                                    }
                                }
                            }
                        }
                    }
                    dialog.show()
                }
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Payments workspace (expanded: search, manual payment, credit note,
    // payment adjustment requests) — separate from the quick "Record
    // payment" action which posts to admin/manual_payment.php.
    // ---------------------------------------------------------------------

    private fun showPaymentsWorkspace() {
        childScreen()
        loadPaymentsWorkspace("")
    }

    private fun loadPaymentsWorkspace(account: String) {
        showLoading("Loading payments workspace")
        val path = if (account.isBlank()) "admin/payments.php" else api.query("admin/payments.php", mapOf("account" to account))
        api.request(path) { result ->
            onResult(result, "Could not load payments workspace") { response ->
                val data = response.data()
                val canReceivePayments = data.optBoolean("can_receive_payments", true)
                val currentUser = data.optJSONObject("current_user")
                val searchHint = data.optJSONObject("field_metadata")
                    ?.optJSONObject("account_number")
                    ?.optString("placeholder")
                    ?.takeIf(String::isNotBlank)
                    ?: "Account, meter number or name"
                val form = screen("Payments workspace", "Search a customer to record payments and adjustments")
                form.addView(heroBanner(
                    title = "Payments workspace",
                    message = "Search a customer, review open bills and capture adjustments from one finance workspace.",
                    eyebrow = "Collections",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_wallet,
                    compact = true
                ))
                val (searchBox, search) = searchableIdentifierField(searchHint, account) { item ->
                    val value = item.optString("selection_value").ifBlank { item.optString("account_number") }
                    childScreen(); loadPaymentsWorkspace(value)
                }
                val searchBtn = actionButton("Search account")
                form.addView(sectionPanel(
                    title = "Find customer account",
                    description = "Look up by account number, meter or customer name."
                ) {
                    addView(searchBox)
                    addView(searchBtn)
                })
                searchBtn.setOnClickListener { childScreen(); loadPaymentsWorkspace(search.text.toString().trim()) }
                if (currentUser != null) {
                    form.addView(summaryCardGrid(listOf(
                        summaryCard("Customer", currentUser.optString("account_number"), R.drawable.ic_person, toneBlue),
                        summaryCard("Wallet", money(data.optDouble("wallet_balance")), R.drawable.ic_wallet, toneTeal),
                        summaryCard("Status", currentUser.optString("status").ifBlank { "Unknown" }, R.drawable.ic_circle_check, toneForStatus(currentUser.optString("status"), toneBlue))
                    )))
                    form.addView(card(currentUser.optString("full_name"), "${currentUser.optString("account_number")} • ${currentUser.optString("status")}"))
                    val bills = data.optJSONArray("bills") ?: JSONArray()
                    form.addView(sectionPanel(
                        title = "Open bills",
                        description = "Target a specific invoice or post to the customer balance, similar to the web payments workspace."
                    ) {
                        if (bills.length() == 0) addView(empty("No open bills."))
                        for (index in 0 until bills.length()) {
                            val bill = bills.optJSONObject(index) ?: continue
                            val billId = bill.optInt("id")
                            addView(card("${bill.optString("billing_month")} • ${money(bill.optDouble("outstanding_amount"))} due", bill.optString("status"), toneForStatus(bill.optString("status"), toneBlue)))
                            if (canReceivePayments) {
                                addView(buttonRow(
                                    "Record payment" to { showManualPaymentForm(currentUser.optString("account_number"), billId, "invoice", data) },
                                    "Credit note" to { showCreditNoteForm(currentUser.optString("account_number"), billId) }
                                ))
                            }
                        }
                        if (canReceivePayments) {
                            val recordBalance = secondaryButton("Record payment to account balance")
                            recordBalance.setOnClickListener { showManualPaymentForm(currentUser.optString("account_number"), 0, "balance", data) }
                            addView(recordBalance)
                        }
                    })
                    val payments = data.optJSONArray("payments") ?: JSONArray()
                    form.addView(sectionPanel(
                        title = "Completed payments",
                        description = "Review settled transactions and raise correction requests when needed."
                    ) {
                        if (payments.length() == 0) addView(empty("No completed payments."))
                        for (index in 0 until payments.length()) {
                            val payment = payments.optJSONObject(index) ?: continue
                            val paymentId = payment.optInt("id")
                            addView(card(money(payment.optDouble("amount")), "${payment.optString("status")} • ${payment.optString("transaction_date")}"))
                            val adjust = secondaryButton("Request adjustment")
                            adjust.setOnClickListener { showPaymentAdjustmentForm(currentUser.optString("account_number"), paymentId) }
                            addView(adjust)
                        }
                    })
                    form.addView(sectionPanel(
                        title = "Adjustment requests",
                        description = "Pending and processed payment adjustment requests for this customer."
                    ) {
                        addRecordList(this, "Adjustment requests", data.optJSONArray("payment_adjustments"), listOf("adjustment_type", "status"))
                    })
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showManualPaymentForm(accountNumber: String, billId: Int, target: String, paymentWorkspaceData: JSONObject) {
        backAction = { loadPaymentsWorkspace(accountNumber) }
        val targetOptions = fieldOptions(
            paymentWorkspaceData,
            "payment_target",
            "payment_target_options",
            listOf("invoice" to "Invoice", "balance" to "Outstanding Balance")
        )
        val methodOptions = fieldOptions(
            paymentWorkspaceData,
            "payment_method",
            "payment_method_options",
            listOf(
                "mpesa" to "M-Pesa",
                "cash" to "Cash",
                "bank" to "Bank Transfer",
                "card" to "Card",
                "cheque" to "Cheque",
                "wallet" to "Wallet",
                "other" to "Other"
            )
        )
        val billOptions = fieldSourceOptions(
            paymentWorkspaceData,
            paymentWorkspaceData.optJSONArray("bills")
        )
        val targetLabel = fieldLabel(paymentWorkspaceData, "payment_target", "Payment target")
        val billLabel = fieldLabel(paymentWorkspaceData, "bill_id", "Invoice")
        val amountLabel = fieldLabel(paymentWorkspaceData, "amount", "Amount")
        val methodLabel = fieldLabel(paymentWorkspaceData, "payment_method", "Payment method")
        val referenceLabel = fieldLabel(paymentWorkspaceData, "payment_reference", "Payment reference")
        val paidDateLabel = fieldLabel(paymentWorkspaceData, "paid_date", "Paid date")
        val paidTimeLabel = fieldLabel(paymentWorkspaceData, "paid_time", "Paid time")
        val targetField = dropdownInput(
            targetLabel,
            targetOptions,
            fieldDefaultValue(paymentWorkspaceData, "payment_target", target)
        )
        val billField = if (billOptions.isNotEmpty()) {
            dropdownInput(
                billLabel,
                billOptions,
                billId.takeIf { it > 0 }?.toString().orEmpty()
            )
        } else null
        val amount = input(amountLabel, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val method = dropdownInput(methodLabel, methodOptions, fieldDefaultValue(paymentWorkspaceData, "payment_method", "cash"))
        val reference = input(referenceLabel)
        val paidDate = datePickerInput(
            paidDateLabel,
            fieldDefaultValue(paymentWorkspaceData, "paid_date", today()),
            fieldPickerMode(paymentWorkspaceData, "paid_date", "date")
        )
        val paidTime = datePickerInput(
            paidTimeLabel,
            fieldDefaultValue(paymentWorkspaceData, "paid_time", ""),
            fieldPickerMode(paymentWorkspaceData, "paid_time", "time")
        )
        val phone = input("Phone number (optional)", InputType.TYPE_CLASS_PHONE)
        val note = multilineInput("Note (optional)")
        val save = actionButton("Record payment")
        val form = screen("Record payment", "$accountNumber • target: $target")
        form.addView(targetField)
        billField?.let(form::addView)
        listOf(amount, method, reference, paidDate, paidTime, phone, note, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            if (amount.text.isBlank() || paidDate.text.isBlank()) {
                toast("Enter the amount and paid date.")
                return@setOnClickListener
            }
            val selectedTarget = targetField.tag?.toString().orEmpty()
            val selectedBillId = billField?.tag?.toString()?.toIntOrNull() ?: billId
            if (selectedTarget == "invoice" && selectedBillId <= 0) {
                toast("Choose the invoice to receipt.")
                return@setOnClickListener
            }
            val body = JSONObject()
                .put("action", "manual_payment")
                .put("account_number", accountNumber)
                .put("bill_id", selectedBillId)
                .put("payment_target", selectedTarget)
                .put("payment_method", method.tag?.toString().orEmpty())
                .put("payment_reference", reference.text.toString().trim())
                .put("amount", amount.text.toString().toDoubleOrNull() ?: 0.0)
                .put("paid_date", paidDate.text.toString().trim())
                .put("paid_time", paidTime.text.toString().trim())
                .put("phone_number", phone.text.toString().trim())
                .put("payment_note", note.text.toString().trim())
            setLoading(save, true, "Record payment")
            api.request("admin/payments.php", "POST", body) { r ->
                runOnUiThread {
                    setLoading(save, false, "Record payment")
                    r.onSuccess { toast(it.optString("message", "Payment recorded.")); loadPaymentsWorkspace(accountNumber) }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showCreditNoteForm(accountNumber: String, billId: Int) {
        backAction = { showPaymentsWorkspace() }
        val type = dropdownInput(
            "Credit type",
            listOf("full" to "Full credit", "partial" to "Partial credit"),
            "full"
        )
        val units = input("Units to credit (for partial)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val note = multilineInput("Note")
        val save = actionButton("Apply credit note")
        val form = screen("Credit note", "Bill #$billId")
        listOf(type, units, note, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            setLoading(save, true, "Apply credit note")
            api.request(
                "admin/payments.php", "POST",
                JSONObject().put("action", "credit_note").put("account_number", accountNumber).put("bill_id", billId)
                    .put("credit_type", type.tag?.toString().orEmpty())
                    .put("units", units.text.toString().toDoubleOrNull() ?: 0.0)
                    .put("note", note.text.toString().trim())
            ) { r ->
                runOnUiThread {
                    setLoading(save, false, "Apply credit note")
                    r.onSuccess { toast(it.optString("message", "Credit note applied.")); loadPaymentsWorkspace(accountNumber) }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    private fun showPaymentAdjustmentForm(accountNumber: String, paymentId: Int) {
        backAction = { showPaymentsWorkspace() }
        val type = dropdownInput(
            "Adjustment type",
            listOf("refund" to "Refund", "correction" to "Correction"),
            "refund"
        )
        val amount = input("Amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val reason = multilineInput("Reason")
        val save = actionButton("Submit adjustment request")
        val form = screen("Payment adjustment", "Payment #$paymentId")
        listOf(type, amount, reason, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            setLoading(save, true, "Submit adjustment request")
            api.request(
                "admin/payments.php", "POST",
                JSONObject().put("action", "payment_adjustment_request").put("account_number", accountNumber).put("payment_id", paymentId)
                    .put("adjustment_type", type.tag?.toString().orEmpty())
                    .put("amount", amount.text.toString().toDoubleOrNull() ?: 0.0)
                    .put("reason", reason.text.toString().trim())
            ) { r ->
                runOnUiThread {
                    setLoading(save, false, "Submit adjustment request")
                    r.onSuccess { toast(it.optString("message", "Adjustment request submitted.")); loadPaymentsWorkspace(accountNumber) }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Payment transactions
    // ---------------------------------------------------------------------

    private fun showPaymentTransactions() {
        childScreen()
        showLoading("Loading payment transactions")
        api.request("admin/payment_transactions.php") { result ->
            onResult(result, "Could not load payment transactions") { response ->
                val data = response.data()
                val form = screen("Payment transactions", "Transaction history and status")
                renderNode(form, "Counts", data.opt("counts"))
                val payments = data.optJSONArray("payments") ?: JSONArray()
                form.addView(sectionTitle("Transactions (${payments.length()})"))
                if (payments.length() == 0) form.addView(empty("No payment transactions found."))
                for (index in 0 until payments.length()) {
                    val payment = payments.optJSONObject(index) ?: continue
                    form.addView(card(
                        "${money(payment.optDouble("amount"))} • ${payment.optString("status")}",
                        "${payment.optString("full_name")} • ${payment.optString("account_number")}\n" +
                            "${payment.optString("payment_method")} • ${payment.optString("transaction_date", payment.optString("created_at"))}"
                    ))
                    if (payment.optString("status") == "failed" && !payment.optBoolean("is_mpesa_payment")) {
                        val request = secondaryButton("Request finance approval")
                        val paymentId = payment.optInt("id")
                        request.setOnClickListener {
                            postAction("admin/payment_transactions.php", JSONObject().put("action", "request_approval").put("payment_id", paymentId)) { showPaymentTransactions() }
                        }
                        form.addView(request)
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Registration proformas
    // ---------------------------------------------------------------------

    private fun showRegistrationProformas() {
        childScreen()
        showLoading("Loading registration proformas")
        api.request("admin/registration_proformas.php") { result ->
            onResult(result, "Could not load registration proformas") { response ->
                val data = response.data()
                val fee = data.optDouble("registration_fee")
                val form = screen("Registration proformas", "Registration fee: ${money(fee)}")
                val create = actionButton("New registration proforma")
                create.setOnClickListener { showCreateProforma(fee, data) }
                form.addView(create)
                val proformas = data.optJSONArray("proformas") ?: JSONArray()
                form.addView(sectionTitle("Proformas (${proformas.length()})"))
                if (proformas.length() == 0) form.addView(empty("No registration proformas found."))
                for (index in 0 until proformas.length()) {
                    val proforma = proformas.optJSONObject(index) ?: continue
                    form.addView(card(
                        "${proforma.optString("full_name")} • ${proforma.optString("account_number")}",
                        "${proforma.optString("user_status")} • ${money(proforma.optDouble("outstanding_amount"))} outstanding"
                    ))
                    proforma.optString("share_url").takeIf(String::isNotBlank)?.let { shareUrl ->
                        form.addView(secondaryButton("Share payment link").apply {
                            setOnClickListener { shareLink(normalizeExternalUrl(shareUrl), "Registration proforma") }
                        })
                    }
                    if (proforma.optString("user_status") != "active") {
                        val id = proforma.optInt("id")
                        val sendStk = secondaryButton("Send M-Pesa STK push")
                        sendStk.setOnClickListener {
                            postAction("admin/registration_proformas.php", JSONObject().put("form_type", "send_stk").put("proforma_id", id)) { showRegistrationProformas() }
                        }
                        form.addView(sendStk)
                    }
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showCreateProforma(fee: Double, data: JSONObject) {
        backAction = ::showRegistrationProformas
        val formMetadata = data.optJSONObject("form_metadata") ?: JSONObject()
        val customerTypeOptions = formMetaOptions(
            formMetadata,
            "customer_type_options",
            listOf("individual" to "Individual", "company" to "Company")
        )
        val countryOptions = formMetaOptions(formMetadata, "country_code_options", listOf("254" to "Kenya (+254)"))
        val connectionOptions = formMetaOptions(
            formMetadata,
            "connection_type_options",
            listOf("domestic" to "Domestic", "commercial" to "Commercial", "industrial" to "Industrial")
        )
        val customerType = dropdownInput("Customer type", customerTypeOptions, "individual")
        val firstName = input("First name / contact first name")
        val middleName = input("Middle name (optional)")
        val lastName = input("Last name / contact last name")
        val companyName = input("Company name (company only)")
        val companyReg = input("Company registration number (company only)")
        val phoneCode = dropdownInput("Phone country code", countryOptions, formMetadata.optString("default_country_code").ifBlank { "254" })
        val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE)
        val email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val idNumber = input("ID number (individual)")
        val address = input("Address")
        val taxPin = input("Tax PIN (optional)")
        val connectionType = dropdownInput("Connection type", connectionOptions, "domestic")
        val unitRate = input("Custom unit rate (optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val notes = multilineInput("Notes (optional)")
        val create = actionButton("Create proforma")
        val form = screen("New registration proforma", "Registration fee: ${money(fee)}")
        listOf(
            customerType, firstName, middleName, lastName, companyName, companyReg, phoneCode, phoneLocal,
            email, idNumber, address, taxPin, connectionType, unitRate, notes, create
        ).forEach(form::addView)
        addBack(form)
        create.setOnClickListener {
            val body = JSONObject()
                .put("form_type", "create_proforma")
                .put("customer_type", customerType.tag?.toString().orEmpty())
                .put("first_name", firstName.text.toString().trim())
                .put("middle_name", middleName.text.toString().trim())
                .put("last_name", lastName.text.toString().trim())
                .put("company_name", companyName.text.toString().trim())
                .put("company_registration_number", companyReg.text.toString().trim())
                .put("phone_country_code", phoneCode.tag?.toString().orEmpty())
                .put("phone_number_local", phoneLocal.text.toString().trim())
                .put("email", email.text.toString().trim())
                .put("id_number", idNumber.text.toString().trim())
                .put("address", address.text.toString().trim())
                .put("tax_pin", taxPin.text.toString().trim())
                .put("connection_type", connectionType.tag?.toString().orEmpty())
                .put("unit_rate", unitRate.text.toString().trim())
                .put("notes", notes.text.toString().trim())
            setLoading(create, true, "Create proforma")
            api.request("admin/registration_proformas.php", "POST", body) { r ->
                runOnUiThread {
                    setLoading(create, false, "Create proforma")
                    r.onSuccess { toast(it.optString("message", "Proforma created.")); showRegistrationProformas() }.onFailure(::handleError)
                }
            }
        }
        show(form)
    }

    // ---------------------------------------------------------------------
    // Invoicing
    // ---------------------------------------------------------------------

    private fun showInvoicing() {
        childScreen()
        showLoading("Loading invoicing")
        api.request("admin/invoicing.php") { result ->
            onResult(result, "Could not load invoicing") { response ->
                val data = response.data()
                val metadata = data.optJSONObject("field_metadata") ?: JSONObject()
                val identifierHint = metadata.optJSONObject("account_or_meter")?.optString("placeholder")
                    ?.takeIf(String::isNotBlank)
                    ?: "Account, meter number or name"
                val (identifierBox, identifier) = searchableIdentifierField(identifierHint)
                val reading = input(fieldLabel(data, "current_reading", "Current reading"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                val billingMonth = datePickerInput(
                    fieldLabel(data, "billing_month", "Billing month"),
                    fieldDefaultValue(data, "billing_month", data.optString("default_billing_month")),
                    metadata.optJSONObject("billing_month")?.optString("picker_mode").orEmpty().ifBlank { "date" }
                )
                val dueDate = datePickerInput(
                    fieldLabel(data, "due_date", "Due date"),
                    fieldDefaultValue(data, "due_date", data.optString("default_due_date")),
                    metadata.optJSONObject("due_date")?.optString("picker_mode").orEmpty().ifBlank { "date" }
                )
                val submit = actionButton("Create bill from reading")
                val form = screen("Invoicing", "Create a pending bill from a meter reading")
                form.addView(heroBanner(
                    title = "Invoicing",
                    message = "Create pending bills using the same billing defaults and search flow exposed by the server.",
                    eyebrow = "Billing workspace",
                    tone = toneBlue,
                    iconRes = R.drawable.ic_receipt,
                    compact = true
                ))
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Billing month", fieldDefaultValue(data, "billing_month", data.optString("default_billing_month")), R.drawable.ic_circle_clock, toneBlue),
                    summaryCard("Due date", fieldDefaultValue(data, "due_date", data.optString("default_due_date")), R.drawable.ic_circle_check, toneTeal),
                    summaryCard("Rate", money(data.optJSONObject("settings")?.optDouble("rate_per_unit") ?: 0.0), R.drawable.ic_wallet, toneAmber),
                    summaryCard("Service charge", money(data.optJSONObject("settings")?.optDouble("service_charge") ?: 0.0), R.drawable.ic_receipt, toneBlue)
                )))
                form.addView(sectionPanel(
                    title = "Create bill from reading",
                    description = "Search for an account or meter, then use the server-provided billing defaults for the reading entry."
                ) {
                    addView(identifierBox)
                    listOf(reading, billingMonth, dueDate, submit).forEach(::addView)
                })
                addBack(form)
                submit.setOnClickListener {
                    if (identifier.text.isBlank() || reading.text.isBlank()) {
                        toast("Enter the account and reading.")
                        return@setOnClickListener
                    }
                    setLoading(submit, true, "Create bill from reading")
                    api.request(
                        "admin/invoicing.php", "POST",
                        JSONObject().put("action", "add_reading")
                            .put("account_or_meter", identifier.text.toString().trim())
                            .put("current_reading", reading.text.toString().toDoubleOrNull() ?: 0.0)
                            .put("billing_month", billingMonth.text.toString().trim())
                            .put("due_date", dueDate.text.toString().trim())
                    ) { r ->
                        runOnUiThread {
                            setLoading(submit, false, "Create bill from reading")
                            r.onSuccess { toast(it.optString("message", "Reading submitted.")); showInvoicing() }.onFailure(::handleError)
                        }
                    }
                }
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Bill correction
    // ---------------------------------------------------------------------

    private fun showBillCorrection() {
        childScreen()
        loadBillCorrection("", 0)
    }

    private fun loadBillCorrection(query: String, selectedBillId: Int) {
        showLoading("Loading bill correction")
        val params = mutableMapOf("q" to query)
        if (selectedBillId > 0) params["bill_id"] = selectedBillId.toString()
        api.request(api.query("admin/bill_correction.php", params)) { result ->
            onResult(result, "Could not load bill correction workspace") { response ->
                val data = response.data()
                val form = screen("Bill correction", "Fix wrongly entered meter readings")
                val (searchBox, search) = searchableIdentifierField("Account, meter number or name", query) { item ->
                    val value = item.optString("selection_value").ifBlank { item.optString("account_number") }
                    childScreen(); loadBillCorrection(value, 0)
                }
                val searchBtn = actionButton("Search")
                form.addView(searchBox)
                form.addView(searchBtn)
                searchBtn.setOnClickListener { childScreen(); loadBillCorrection(search.text.toString().trim(), 0) }
                val currentUser = data.optJSONObject("current_user")
                if (currentUser != null) {
                    form.addView(card(currentUser.optString("full_name"), currentUser.optString("account_number")))
                    val bills = data.optJSONArray("bills") ?: JSONArray()
                    form.addView(sectionTitle("Bills"))
                    if (bills.length() == 0) form.addView(empty("No correctable bills found."))
                    for (index in 0 until bills.length()) {
                        val bill = bills.optJSONObject(index) ?: continue
                        val row = card("${bill.optString("billing_month")} • ${money(bill.optDouble("amount"))}", "${bill.optString("status")} • reading ${bill.optString("current_reading")}")
                        row.isClickable = true
                        row.isFocusable = true
                        val billId = bill.optInt("id")
                        row.setOnClickListener { childScreen(); loadBillCorrection(query, billId) }
                        form.addView(row)
                    }
                }
                val selectedBill = data.optJSONObject("selected_bill")
                if (selectedBill != null) {
                    form.addView(sectionTitle("Correct reading"))
                    addField(form, "Bill", "${selectedBill.optString("billing_month")} • ${money(selectedBill.optDouble("amount"))}")
                    val newReading = input("New current reading", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                    val reason = multilineInput("Reason for correction")
                    val apply = actionButton("Apply correction")
                    form.addView(newReading)
                    form.addView(reason)
                    form.addView(apply)
                    apply.setOnClickListener {
                        if (newReading.text.isBlank() || reason.text.isBlank()) {
                            toast("Enter the new reading and a reason.")
                            return@setOnClickListener
                        }
                        setLoading(apply, true, "Apply correction")
                        api.request(
                            "admin/bill_correction.php", "POST",
                            JSONObject().put("action", "apply_correction").put("bill_id", selectedBillId)
                                .put("new_reading", newReading.text.toString().toDoubleOrNull() ?: 0.0)
                                .put("reason", reason.text.toString().trim())
                        ) { r ->
                            runOnUiThread {
                                setLoading(apply, false, "Apply correction")
                                r.onSuccess {
                                    toast(it.optString("message", "Bill corrected."))
                                    childScreen()
                                    loadBillCorrection(query, 0)
                                }.onFailure(::handleError)
                            }
                        }
                    }
                    addRecordList(form, "Correction history", data.optJSONArray("history"), listOf("reason"))
                }
                addBack(form)
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Reports
    // ---------------------------------------------------------------------

    private fun showReports() {
        childScreen()
        loadReports("this_month")
    }

    private fun loadReports(period: String) {
        showLoading("Loading reports")
        api.request(api.query("admin/reports.php", mapOf("period" to period))) { result ->
            onResult(result, "Could not load reports") { response ->
                val data = response.data()
                val periodKey = data.optJSONObject("period")?.optString("key") ?: period
                val form = screen("Reports", "Period: $periodKey")
                form.addView(heroBanner(
                    title = "Operational reports",
                    message = "Track billing, collections and audit health for the selected period.",
                    eyebrow = periodKey.replace('_', ' ').replaceFirstChar(Char::uppercase),
                    tone = toneTeal,
                    iconRes = R.drawable.ic_wallet,
                    compact = true
                ))
                form.addView(buttonRow(
                    "Today" to { childScreen(); loadReports("today") },
                    "This month" to { childScreen(); loadReports("this_month") },
                    "This year" to { childScreen(); loadReports("this_year") }
                ))
                val summary = data.optJSONObject("summary") ?: JSONObject()
                form.addView(summaryCardGrid(listOf(
                    summaryCard("Billed total", money(summary.optDouble("billed_total")), R.drawable.ic_receipt, toneBlue),
                    summaryCard("Completed payments", money(summary.optDouble("completed_payments_total")), R.drawable.ic_wallet, toneTeal)
                )))
                addPaymentRows(form, data.optJSONArray("recent_payments"), true)
                addRecordList(form, "Recent bills", data.optJSONArray("recent_bills"), listOf("account_number", "full_name"))
                renderNode(form, "Audit status", data.opt("audit_status"))
                form.addView(sectionTitle("Maintenance"))
                val runAudit = secondaryButton("Run billing integrity audit")
                runAudit.setOnClickListener { runReportsMaintenance("run_audit") }
                form.addView(runAudit)
                val previewRepair = secondaryButton("Preview journal repair")
                previewRepair.setOnClickListener { runReportsMaintenance("preview_repair") }
                form.addView(previewRepair)
                val applyRepair = secondaryButton("Apply journal repair")
                applyRepair.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Apply journal repair")
                        .setMessage("This will modify accounting journal entries. Continue?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Apply") { _, _ -> runReportsMaintenance("apply_repair") }
                        .show()
                }
                form.addView(applyRepair)
                addBack(form)
                show(form)
            }
        }
    }

    private fun runReportsMaintenance(action: String) {
        api.request("admin/reports.php", "POST", JSONObject().put("maintenance_action", action)) { result ->
            runOnUiThread {
                result.onSuccess { toast(it.optString("message", "Maintenance action completed.")); loadReports("this_month") }.onFailure(::handleError)
            }
        }
    }

    private fun fileName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "Meter photo selected"
    }

    private fun childScreen() {
        // The shown view history supplies the concrete previous screen.
    }

    private fun showLoading(message: String) {
        val form = screen("My Water Bill", message)
        form.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(primaryDark)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(36)
                bottomMargin = dp(12)
            }
        })
        form.addView(body("Please wait...").apply { gravity = Gravity.CENTER })
        show(form, transient = true)
        pendingScreenLoad = null
        recordingScreenLoad = true
    }

    private fun show(form: LinearLayout, transient: Boolean = false) {
        closeActivePdf()
        screenEpoch++
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = WaterDropsDrawable(this@MainActivity)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            val contentWidth = if (resources.configuration.screenWidthDp >= 760) dp(720) else ViewGroup.LayoutParams.MATCH_PARENT
            addView(
                form,
                FrameLayout.LayoutParams(
                    contentWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        val screenLoad = if (transient) null else pendingScreenLoad
        if (!transient) {
            recordingScreenLoad = false
            pendingScreenLoad = null
        }
        val root = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(primaryDark)
            setProgressBackgroundColorSchemeColor(cardBackground)
            isEnabled = screenLoad != null
            addView(scroll)
        }
        root.setOnRefreshListener { refreshScreen(root) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        root.post {
            ViewCompat.requestApplyInsets(root)
        }
        // Status/navigation bar backgrounds and icon appearance both derive from
        // the effective night mode: dark background + light icons at night,
        // light background + dark icons otherwise.
        val lightBars = !isNightModeActive()
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        if (!transient) {
            val previous = displayedScreen
            if (replaceOnNextShow) {
                replaceOnNextShow = false
            } else {
                if (previous != null) {
                    navigationHistory += previous
                    if (navigationHistory.size > 30) navigationHistory.removeAt(0)
                }
                forwardHistory.clear()
            }
            displayedScreen = ScreenSnapshot(root, backAction, screenLoad)
        }
    }

    // Pull-to-refresh replays the GET request that originally built the screen; the
    // refreshed result replaces the current screen in place instead of adding history.
    private fun refreshScreen(root: SwipeRefreshLayout) {
        val load = displayedScreen?.takeIf { it.view === root }?.reload
        if (load == null) {
            root.isRefreshing = false
            return
        }
        replaceOnNextShow = true
        recordingScreenLoad = true
        pendingScreenLoad = load
        api.request(load.path) { result ->
            if (result.isFailure) {
                runOnUiThread {
                    replaceOnNextShow = false
                    recordingScreenLoad = false
                    pendingScreenLoad = null
                    root.isRefreshing = false
                }
            }
            load.callback(result)
        }
    }

    private fun displaySnapshot(snapshot: ScreenSnapshot) {
        closeActivePdf()
        screenEpoch++
        recordingScreenLoad = false
        pendingScreenLoad = null
        replaceOnNextShow = false
        displayedScreen = snapshot
        backAction = snapshot.backAction
        (snapshot.view as? SwipeRefreshLayout)?.isRefreshing = false
        setContentView(snapshot.view)
        ViewCompat.requestApplyInsets(snapshot.view)
    }

    private fun navigateForward(): Boolean {
        val next = forwardHistory.removeLastOrNull() ?: return false
        displayedScreen?.let { navigationHistory += it }
        displaySnapshot(next)
        return true
    }

    private fun canScrollHorizontallyAt(view: View, x: Float, y: Float, direction: Int): Boolean {
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        if (x < location[0] || x > location[0] + view.width || y < location[1] || y > location[1] + view.height) return false
        if (view.canScrollHorizontally(direction)) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (canScrollHorizontallyAt(view.getChildAt(index), x, y, direction)) return true
            }
        }
        return false
    }

    // Horizontal swipe navigation: finger moving left-to-right goes back, right-to-left
    // goes forward. Swipes that start on horizontally scrollable content are ignored.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.rawX
                swipeStartY = event.rawY
                swipeStartTime = event.eventTime
                swipeEligible = displayedScreen != null
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipeEligible = false
            MotionEvent.ACTION_UP -> if (swipeEligible) {
                swipeEligible = false
                val dx = event.rawX - swipeStartX
                val dy = event.rawY - swipeStartY
                val elapsed = event.eventTime - swipeStartTime
                val isSwipe = kotlin.math.abs(dx) > dp(90) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2 && elapsed < 700
                val root = window.decorView
                if (isSwipe && !canScrollHorizontallyAt(root, swipeStartX, swipeStartY, if (dx > 0) -1 else 1)) {
                    val handled = if (dx > 0) navigateBack() else navigateForward()
                    if (handled) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> swipeEligible = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun screen(header: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = header
        setPadding(dp(20), 0, dp(20), dp(36))
        addView(headerBar(header, subtitle), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = -dp(20)
            rightMargin = -dp(20)
            bottomMargin = dp(20)
        })
        backAction?.let { action ->
            addView(backNavigationRow(action))
        }
    }

    private fun headerBar(header: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(22))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = dp(30).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            colors = intArrayOf(navyDark, navy, headerGradientEnd)
            orientation = GradientDrawable.Orientation.TL_BR
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name).uppercase(Locale.getDefault())
                textSize = 11.5f
                letterSpacing = 0.14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(210, 224, 243))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.live_badge)
                textSize = 10.5f
                letterSpacing = 0.12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedDrawable(Color.argb(72, 184, 137, 43), Color.argb(140, 226, 192, 114), 99f)
            })
        })
        addView(title(header).apply { setPadding(0, dp(10), 0, 0) })
        if (subtitle.isNotBlank()) {
            addView(body(subtitle).apply {
                setTextColor(Color.rgb(214, 225, 240))
                textSize = 14.5f
                setPadding(0, dp(8), 0, 0)
            })
        }
        addView(View(this@MainActivity).apply {
            setBackgroundColor(Color.argb(115, 216, 183, 103))
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(3)).apply {
                topMargin = dp(14)
            }
        })
    }

    private fun backNavigationRow(action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        isClickable = true
        isFocusable = true
        contentDescription = "Go back"
        setPadding(dp(14), 0, dp(16), 0)
        background = roundedDrawable(tintNavy, border, 16f)
        elevation = dp(1).toFloat()
        setOnClickListener { navigateBack(action) }
        addView(iconView(R.drawable.ic_chevron_left, primaryDark, 22))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.back)
            textSize = 15f
            setTextColor(textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 24f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setTextColor(Color.WHITE)
        letterSpacing = 0.01f
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setTextColor(headingText)
        letterSpacing = 0.015f
        setPadding(dp(2), dp(24), 0, dp(12))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 15.5f
        setTextColor(muted)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun sectionPanel(
        title: String,
        description: String,
        content: LinearLayout.() -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(cardBackground, border, 24f)
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(headingText)
        })
        if (description.isNotBlank()) {
            addView(body(description).apply {
                setPadding(0, dp(6), 0, dp(14))
            })
        }
        content()
    }

    private fun spotlightPanel(title: String, message: String, meta: String, tone: Tone) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(tone.tint, tone.border, 24f)
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) }
        addView(TextView(this@MainActivity).apply {
            text = title.uppercase(Locale.getDefault())
            textSize = 11f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(tone.onTint)
        })
        addView(TextView(this@MainActivity).apply {
            text = message
            textSize = 19f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(tone.onTint)
            setPadding(0, dp(10), 0, dp(6))
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        if (meta.isNotBlank()) {
            addView(body(meta).apply {
                setTextColor(tone.onTint)
                alpha = 0.82f
            })
        }
    }

    private fun iconView(resId: Int, color: Int, sizeDp: Int = 24) = ImageView(this).apply {
        setImageResource(resId)
        setColorFilter(color, PorterDuff.Mode.SRC_IN)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun labeledField(label: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 14f
            setTextColor(textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(7))
            labelFor = field.id
        })
        field.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        field.minHeight = dp(58)
        addView(field)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun loginFooter() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, 0)
        addView(footerLink("Privacy policy") {
            openExternal(getString(R.string.privacy_policy_url))
        })
        addView(footerLink("Contact support") {
            openExternal("mailto:${getString(R.string.support_email)}")
        })
    }

    private fun footerLink(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(primaryDark)
        minHeight = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun openExternal(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, normalizeExternalUrl(uri).toUri()))
        } catch (_: Exception) {
            toast(getString(R.string.link_unavailable))
        }
    }

    private fun normalizeExternalUrl(url: String): String = runCatching {
        api.resolveUrl(url)
    }.getOrElse {
        url
    }

    private fun empty(value: String) = body(value).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(26), dp(20), dp(26))
        background = roundedDrawable(cardBackgroundMuted, border, 18f)
    }

    private fun input(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            id = View.generateViewId()
            hint = hintText
            inputType = type
            if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) {
                transformationMethod = PasswordTransformationMethod.getInstance()
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0,
                    0,
                    R.drawable.ic_visibility,
                    0
                )
                compoundDrawablePadding = dp(12)
                ViewCompat.setStateDescription(this, getString(R.string.password_hidden))
                val toggleVisibility = {
                    val passwordVisible = transformationMethod == null
                    transformationMethod =
                        if (passwordVisible) PasswordTransformationMethod.getInstance() else null
                    ViewCompat.setStateDescription(
                        this,
                        getString(if (passwordVisible) R.string.password_hidden else R.string.password_visible)
                    )
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0,
                        0,
                        if (passwordVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off,
                        0
                    )
                    setSelection(text.length)
                    true
                }
                setOnTouchListener { view, event ->
                    val endIcon = compoundDrawablesRelative[2]
                    val iconTapped = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                        event.x <= paddingStart + (endIcon?.bounds?.width() ?: 0)
                    } else {
                        event.x >= width - paddingEnd - (endIcon?.bounds?.width() ?: 0)
                    }
                    if (event.action == MotionEvent.ACTION_UP && endIcon != null && iconTapped) {
                        toggleVisibility()
                        view.performClick()
                        true
                    } else {
                        false
                    }
                }
                ViewCompat.addAccessibilityAction(
                    this,
                    "Toggle password visibility"
                ) { _, _ -> toggleVisibility() }
            }
            setSingleLine(true)
            textSize = 16f
            setTextColor(textPrimary)
            setHintTextColor(muted)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = roundedDrawable(cardBackground, border, 16f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            minHeight = dp(60)
        }

    private fun multilineInput(hintText: String) = EditText(this).apply {
        hint = hintText
        minLines = 4
        gravity = Gravity.TOP
        textSize = 16f
        setTextColor(textPrimary)
        setHintTextColor(muted)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedDrawable(cardBackground, border, 16f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    // ---------------------------------------------------------------------
    // Debounced customer/account/meter autocomplete, shared by the staff
    // billing, payments, customer management, invoicing and bill-correction
    // lookup fields. Reuses admin/search_clients.php?q=..., whose response
    // now exposes clients/data/results/suggestions (all equivalent) — each
    // suggestion carries a server-computed `selection_value` (the account
    // number, or the specific meter number when the query matched a meter)
    // so selecting a suggestion always fills the field with the exact
    // identifier rather than free text.
    // ---------------------------------------------------------------------

    private fun searchableIdentifierField(
        hint: String,
        initialValue: String = "",
        onSelect: (JSONObject) -> Unit = {}
    ): Pair<LinearLayout, EditText> {
        val field = input(hint).apply {
            setText(initialValue)
            setSelection(text.length)
        }
        val suggestionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(field)
            addView(suggestionsBox)
        }
        var searchToken = 0
        var pendingLookup: Runnable? = null
        var suppressNext = false
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (suppressNext) {
                    suppressNext = false
                    return
                }
                pendingLookup?.let { mainHandler.removeCallbacks(it) }
                suggestionsBox.removeAllViews()
                suggestionsBox.visibility = View.GONE
                val query = s?.toString()?.trim().orEmpty()
                if (query.length < 2) {
                    searchToken++
                    return
                }
                val myToken = ++searchToken
                val lookup = Runnable {
                    api.request(api.query("admin/search_clients.php", mapOf("q" to query))) { result ->
                        runOnUiThread {
                            if (myToken != searchToken) return@runOnUiThread
                            result.onSuccess { response ->
                                val data = response.optJSONObject("data") ?: response
                                val items = response.optJSONArray("data")
                                    ?: data.optJSONArray("suggestions")
                                    ?: data.optJSONArray("results")
                                    ?: data.optJSONArray("data")
                                    ?: data.optJSONArray("clients")
                                    ?: data.optJSONArray("customers")
                                    ?: JSONArray()
                                suggestionsBox.removeAllViews()
                                if (items.length() == 0) {
                                    suggestionsBox.visibility = View.GONE
                                    return@onSuccess
                                }
                                suggestionsBox.visibility = View.VISIBLE
                                for (i in 0 until items.length()) {
                                    val item = items.optJSONObject(i) ?: continue
                                    val title = item.optString("suggestion_text")
                                        .ifBlank { item.optString("full_name") }
                                        .ifBlank { item.optString("name") }
                                        .ifBlank { item.optString("account_number") }
                                        .ifBlank { item.optString("meter_number") }
                                    val subtitle = listOf(item.optString("phone_number"), item.optString("status"))
                                        .filter(String::isNotBlank).joinToString(" • ")
                                    suggestionsBox.addView(suggestionRow(title, subtitle) {
                                        val value = item.optString("selection_value")
                                            .ifBlank { item.optString("account_number") }
                                            .ifBlank { item.optString("meter_number") }
                                        suppressNext = true
                                        field.setText(value)
                                        field.setSelection(field.text.length)
                                        suggestionsBox.removeAllViews()
                                        suggestionsBox.visibility = View.GONE
                                        getSystemService(InputMethodManager::class.java)
                                            ?.hideSoftInputFromWindow(field.windowToken, 0)
                                        onSelect(item)
                                    })
                                }
                            }.onFailure { error ->
                                suggestionsBox.removeAllViews()
                                suggestionsBox.visibility = View.VISIBLE
                                suggestionsBox.addView(body(
                                    "Suggestions unavailable: ${error.message ?: "Could not search customers."}"
                                ).apply {
                                    setTextColor(danger)
                                    setPadding(dp(8), dp(4), dp(8), dp(8))
                                })
                            }
                        }
                    }
                }
                pendingLookup = lookup
                mainHandler.postDelayed(lookup, 400L)
            }
        })
        return container to field
    }

    private fun dropdownInput(
        hintText: String,
        options: List<Pair<String, String>>,
        initialValue: String
    ): EditText {
        val field = input(hintText).apply {
            keyListener = null
            isFocusable = false
            isClickable = true
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
            compoundDrawablePadding = dp(10)
        }
        if (options.isEmpty()) {
            field.setText(R.string.no_options_available)
            field.isEnabled = false
            return field
        }
        fun showOptions() {
            val selectedIndex = options.indexOfFirst { it.first == field.tag }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(hintText)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selectedIndex) { dialog, which ->
                    val (value, label) = options[which]
                    field.tag = value
                    field.setText(label)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        field.setOnClickListener { showOptions() }
        field.tag = options.firstOrNull { it.first == initialValue }?.first
            ?: options.firstOrNull()?.first.orEmpty()
        field.setText(options.firstOrNull { it.first == field.tag }?.second.orEmpty())
        return field
    }

    private fun accountOptions(accounts: JSONArray, includeInactive: Boolean = false): List<Pair<String, String>> =
        (0 until accounts.length()).mapNotNull { index ->
            val account = accounts.optJSONObject(index) ?: return@mapNotNull null
            val id = account.optInt("id")
            if (id <= 0 || (!includeInactive && !account.optBoolean("is_active", true))) return@mapNotNull null
            val code = account.optString("code").trim()
            val name = account.optString("name").trim()
            val type = formatAccountType(account.optString("account_type"))
            val title = listOf(code, name).filter(String::isNotBlank).joinToString(" • ")
            val status = if (account.optBoolean("is_active", true)) "Active" else "Inactive"
            (id.toString()) to listOfNotNull(title, type, status.takeIf { includeInactive }).filter(String::isNotBlank).joinToString(" — ")
        }

    private fun accountTypeOptions(): List<Pair<String, String>> {
        val options = mutableListOf<Pair<String, String>>()
        options += "" to "All account types"
        options += listOf(
            "asset" to "Asset",
            "liability" to "Liability",
            "equity" to "Equity",
            "revenue" to "Revenue",
            "expense" to "Expense",
            "cost_of_sales" to "Cost of sales"
        )
        return options
    }

    private fun formatAccountType(value: String): String =
        value.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    private fun renderFinanceSummary(parent: LinearLayout, summary: JSONObject?) {
        if (summary == null || summary.length() == 0) return
        val cards = mutableListOf<View>()
        val detailNodes = mutableListOf<Pair<String, Any?>>()
        summary.keys().asSequence().sorted().forEach { key ->
            val value = summary.opt(key)
            val label = key.replace('_', ' ').replaceFirstChar(Char::uppercase)
            if (value is JSONObject || value is JSONArray) {
                detailNodes += label to value
            } else if (value != JSONObject.NULL) {
                val lowerKey = key.lowercase()
                val tone = when {
                    listOf("overdue", "error", "failed", "variance").any(lowerKey::contains) -> toneAmber
                    listOf("paid", "collected", "success", "available").any(lowerKey::contains) -> toneTeal
                    else -> toneBlue
                }
                val icon = when {
                    listOf("payment", "cash", "revenue", "income").any(lowerKey::contains) -> R.drawable.ic_wallet
                    listOf("account", "ledger", "journal").any(lowerKey::contains) -> R.drawable.ic_receipt
                    else -> R.drawable.ic_circle_check
                }
                cards += summaryCard(label, formatValue(key, value), icon, tone)
            }
        }
        if (cards.isNotEmpty()) {
            parent.addView(sectionTitle("At a glance"))
            parent.addView(summaryCardGrid(cards))
        }
        detailNodes.forEach { (label, value) -> renderFinanceNode(parent, label, value) }
    }

    private fun renderFinanceNode(parent: LinearLayout, label: String, value: Any?) {
        when (value) {
            is JSONObject -> {
                val metrics = mutableListOf<View>()
                val nested = mutableListOf<Pair<String, Any?>>()
                value.keys().asSequence().sorted().forEach { key ->
                    val child = value.opt(key)
                    val childLabel = key.replace('_', ' ').replaceFirstChar(Char::uppercase)
                    if (child is JSONObject || child is JSONArray) {
                        nested += childLabel to child
                    } else if (child != JSONObject.NULL) {
                        val tone = when {
                            "overdue" in key.lowercase() || "variance" in key.lowercase() -> toneAmber
                            "paid" in key.lowercase() || "collected" in key.lowercase() -> toneTeal
                            else -> toneBlue
                        }
                        metrics += summaryCard(
                            childLabel,
                            formatValue(key, child),
                            R.drawable.ic_receipt,
                            tone
                        )
                    }
                }
                if (metrics.isNotEmpty()) {
                    parent.addView(sectionTitle(label))
                    parent.addView(summaryCardGrid(metrics))
                }
                nested.forEach { (childLabel, child) ->
                    renderFinanceNode(parent, "$label • $childLabel", child)
                }
            }
            is JSONArray -> addRecordList(parent, label, value)
            else -> if (value != null && value != JSONObject.NULL) {
                addField(parent, label, formatValue(label, value))
            }
        }
    }

    private fun fieldOptions(
        data: JSONObject,
        fieldKey: String,
        topLevelKey: String? = null,
        fallback: List<Pair<String, String>> = emptyList()
    ): List<Pair<String, String>> {
        val metadataOptions = data.optJSONObject("field_metadata")
            ?.optJSONObject(fieldKey)
            ?.optJSONArray("options")
            ?.toFlexibleOptionPairs()
            .orEmpty()
        if (metadataOptions.isNotEmpty()) return metadataOptions
        val topLevelOptions = topLevelKey?.let { key ->
            data.optJSONArray(key)?.toFlexibleOptionPairs().orEmpty()
        }.orEmpty()
        return topLevelOptions.ifEmpty { fallback }
    }

    private fun formMetaOptions(
        formMetadata: JSONObject,
        key: String,
        fallback: List<Pair<String, String>> = emptyList()
    ): List<Pair<String, String>> {
        val options = formMetadata.optJSONArray(key)?.toFlexibleOptionPairs().orEmpty()
        return options.ifEmpty { fallback }
    }

    private fun splitPhoneNumberForForm(
        phoneNumber: String,
        countryOptions: List<Pair<String, String>>,
        defaultCountryCode: String
    ): Pair<String, String> {
        val digits = phoneNumber.filter(Char::isDigit)
        if (digits.isBlank()) return defaultCountryCode to ""
        val selectedCode = countryOptions
            .map { it.first.filter(Char::isDigit) }
            .filter(String::isNotBlank)
            .sortedByDescending { it.length }
            .firstOrNull { digits.startsWith(it) }
            ?: defaultCountryCode
        val localNumber = digits.removePrefix(selectedCode).ifBlank { digits }
        return selectedCode to localNumber
    }

    private fun fieldDefaultValue(data: JSONObject, fieldKey: String, fallback: String): String {
        val defaultValue = data.optJSONObject("field_metadata")
            ?.optJSONObject(fieldKey)
            ?.opt("default_value")
            ?.toString()
            .orEmpty()
        return defaultValue.ifBlank { fallback }
    }

    private fun fieldLabel(data: JSONObject, fieldKey: String, fallback: String): String =
        data.optJSONObject("field_metadata")
            ?.optJSONObject(fieldKey)
            ?.optString("label")
            .orEmpty()
            .ifBlank { fallback }

    private fun fieldPickerMode(data: JSONObject, fieldKey: String, fallback: String): String =
        data.optJSONObject("field_metadata")
            ?.optJSONObject(fieldKey)
            ?.optString("picker_mode")
            .orEmpty()
            .ifBlank { fallback }

    private fun fieldSourceOptions(data: JSONObject, source: JSONArray?): List<Pair<String, String>> {
        if (source == null || source.length() == 0) return emptyList()
        val field = data.optJSONObject("field_metadata")?.optJSONObject("bill_id") ?: JSONObject()
        val labelKey = field.optString("label_key").ifBlank { "id" }
        val fallbackTemplate = field.optString("fallback_label_template")
        val options = mutableListOf<Pair<String, String>>()
        for (index in 0 until source.length()) {
            val row = source.optJSONObject(index) ?: continue
            val value = row.optInt("id").takeIf { it > 0 }?.toString().orEmpty()
            if (value.isBlank()) continue
            val explicitLabel = row.optString(labelKey).takeIf(String::isNotBlank)
            val label = explicitLabel ?: formatTemplateLabel(fallbackTemplate, row).ifBlank {
                listOf(row.optString("billing_month"), money(row.optDouble("outstanding_amount")))
                    .filter(String::isNotBlank)
                    .joinToString(" • ")
            }
            options += value to label
        }
        return options
    }

    private fun formatTemplateLabel(template: String, row: JSONObject): String {
        if (template.isBlank()) return ""
        var result = template
        val iterator = row.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = if (key.contains("amount") || key.contains("balance")) {
                row.optDouble(key).takeIf { row.opt(key) != JSONObject.NULL }?.let(::money) ?: row.optString(key)
            } else {
                row.optString(key)
            }
            result = result.replace("{$key}", value)
        }
        return result
    }

    private fun addProfileFields(parent: LinearLayout, fields: JSONArray?, user: JSONObject, themePreference: String) {
        if (fields == null || fields.length() == 0) return
        for (index in 0 until fields.length()) {
            val field = fields.optJSONObject(index) ?: continue
            val key = field.optString("key")
            if (key.isBlank()) continue
            val label = field.optString("label").ifBlank {
                key.replace('_', ' ').replaceFirstChar(Char::uppercase)
            }
            val value = when (key) {
                "theme_preference" -> themePreferenceLabel(themePreference)
                "connection_type", "role", "status" -> user.optString(key).replace('_', ' ').replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
                else -> user.optString(key)
            }
            addField(parent, label, value)
        }
    }

    private fun toneForEmphasis(emphasis: String): Tone = when (emphasis.lowercase()) {
        "positive", "success" -> toneTeal
        "warning" -> toneAmber
        "danger", "error" -> toneRed
        else -> toneBlue
    }

    private fun iconForSummaryKey(key: String): Int {
        val normalized = key.lowercase()
        return when {
            listOf("amount", "balance", "collected", "payment").any(normalized::contains) -> R.drawable.ic_wallet
            listOf("bill", "invoice", "statement").any(normalized::contains) -> R.drawable.ic_receipt
            listOf("meter", "reading").any(normalized::contains) -> R.drawable.ic_meter
            listOf("overdue", "alert", "warning").any(normalized::contains) -> R.drawable.ic_warning_triangle
            else -> R.drawable.ic_circle_check
        }
    }

    private fun summaryCardViews(summary: JSONObject, specs: JSONArray?): List<View> {
        val cards = mutableListOf<View>()
        if (specs == null || specs.length() == 0) return cards
        for (index in 0 until specs.length()) {
            val spec = specs.optJSONObject(index) ?: continue
            val key = spec.optString("key")
            val label = spec.optString("label").ifBlank {
                key.replace('_', ' ').replaceFirstChar(Char::uppercase)
            }
            val rawValue = when {
                key.isNotBlank() && summary.has(key) -> summary.opt(key)
                spec.has("value") -> spec.opt("value")
                else -> null
            }
            if (rawValue == null || rawValue == JSONObject.NULL) continue
            cards += summaryCard(
                label,
                formatValue(key.ifBlank { label }, rawValue),
                iconForSummaryKey(key.ifBlank { label }),
                toneForEmphasis(spec.optString("emphasis"))
            )
        }
        return cards
    }

    private fun addScreenAlert(parent: LinearLayout, alert: JSONObject) {
        val title = alert.optString("title").ifBlank { "Notice" }
        val message = alert.optString("message")
        val tone = when (alert.optString("type").lowercase()) {
            "warning" -> toneAmber
            "danger", "error" -> toneRed
            "success", "positive" -> toneTeal
            else -> toneBlue
        }
        parent.addView(statusBanner(title, message, tone, R.drawable.ic_circle_alert, false))
        val action = alert.optJSONObject("action") ?: return
        val spec = mobileActionSpec(action) ?: return
        val button = secondaryButton(spec.first)
        button.setOnClickListener { spec.second() }
        parent.addView(button)
    }

    private fun datePickerInput(hintText: String, initialValue: String, pickerMode: String = "date"): EditText {
        val field = input(hintText).apply {
            keyListener = null
            isFocusable = false
            isClickable = true
            setText(initialValue)
        }
        field.setOnClickListener {
            val calendar = Calendar.getInstance()
            runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                    .parse(field.text.toString())
            }.getOrNull()?.let(calendar::setTime)
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    field.setText(
                        if (pickerMode.equals("month", ignoreCase = true)) {
                            String.format(Locale.US, "%04d-%02d-01", year, month + 1)
                        } else {
                            String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                        }
                    )
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        return field
    }

    private fun suggestionRow(title: String, subtitle: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48)
        background = roundedDrawable(cardBackground, tintBlue, 14f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryDark)
            maxLines = 2
        })
        if (subtitle.isNotBlank()) {
            addView(body(subtitle).apply {
                textSize = 12.5f
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun actionButton(label: String) = MaterialButton(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        cornerRadius = dp(16)
        backgroundTintList = ColorStateList.valueOf(primary)
        elevation = dp(1).toFloat()
        minHeight = dp(56)
        insetTop = 0
        insetBottom = 0
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun secondaryButton(label: String) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label
        setTextColor(primaryDark)
        textSize = 15f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        cornerRadius = dp(16)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(border)
        backgroundTintList = ColorStateList.valueOf(cardBackground)
        minHeight = dp(54)
        insetTop = 0
        insetBottom = 0
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun destructiveButton(label: String) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label
        setTextColor(danger)
        textSize = 15f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        cornerRadius = dp(16)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(danger)
        backgroundTintList = ColorStateList.valueOf(tintRed)
        minHeight = dp(54)
        insetTop = 0
        insetBottom = 0
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun card(label: String, value: String, tone: Tone = toneNeutral) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipToOutline = true
        gravity = Gravity.CENTER_VERTICAL
        background = roundedDrawable(tone.tint, tone.border, 18f)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        tone.accent?.let { accentColor ->
            addView(View(this@MainActivity).apply {
                setBackgroundColor(accentColor)
                layoutParams = LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(muted)
                letterSpacing = 0.06f
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 17.5f
                setTextColor(textPrimary)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            })
        })
    }

    private fun summaryCardGrid(cards: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cards.chunked(2).forEach { rowCards ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                rowCards.forEachIndexed { index, view ->
                    addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
                    })
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
    }

    private fun summaryCard(label: String, value: String, iconRes: Int, tone: Tone) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToOutline = true
        setPadding(dp(16), dp(16), dp(16), dp(18))
        background = roundedDrawable(tone.tint, tone.border, 18f)
        elevation = dp(2).toFloat()
        minimumHeight = dp(126)
        addView(LinearLayout(this@MainActivity).apply {
            background = roundedDrawable(cardBackground, cardBackground, 12f)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            addView(iconView(iconRes, tone.onTint, 20))
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(12) })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 19f
            setTextColor(tone.onTint)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12.5f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
            maxLines = 2
        })
    }

    private fun identityCard(name: String, accountNumber: String, role: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToOutline = true
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedDrawable(cardBackground, border, 18f)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) }
        addView(LinearLayout(this@MainActivity).apply {
            background = roundedDrawable(tintBlue, tintBlue, 24f)
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            addView(iconView(R.drawable.ic_person, primaryDark, 24))
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = name.ifBlank { "Customer" }
                textSize = 18f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(textPrimary)
                maxLines = 1
            })
            addView(body(listOf(accountNumber, role).filter(String::isNotBlank).joinToString(" • ")).apply {
                textSize = 13f
                setPadding(0, dp(2), 0, 0)
            })
        })
    }

    private fun quickActionGrid(items: List<Triple<String, Int, () -> Unit>>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        items.chunked(2).forEach { rowItems ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                rowItems.forEachIndexed { index, (label, iconRes, action) ->
                    addView(quickActionTile(label, iconRes, action), LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { if (index == 0) marginEnd = dp(6) else marginStart = dp(6) })
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
    }

    private fun quickActionTile(label: String, iconRes: Int, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToOutline = true
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = roundedDrawable(cardBackground, border, 18f)
        elevation = dp(2).toFloat()
        setOnClickListener { action() }
        addView(LinearLayout(this@MainActivity).apply {
            background = roundedDrawable(tintNavy, border, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(iconView(iconRes, primaryDark, 18))
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(12) })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            maxLines = 2
        })
    }

    private fun addField(parent: LinearLayout, label: String, value: String) {
        parent.addView(card(label, value.ifBlank { "—" }))
    }

    private fun addWorkspace(
        parent: LinearLayout,
        label: String,
        description: String,
        iconRes: Int,
        action: () -> Unit,
        tone: Tone = toneBlue
    ) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipToOutline = true
            setPadding(dp(16), dp(18), dp(15), dp(18))
            background = roundedDrawable(cardBackground, border, 18f)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "$label. $description"
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(11) }
            addView(LinearLayout(this@MainActivity).apply {
                background = roundedDrawable(tone.tint, tone.tint, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(11), dp(11), dp(11))
                addView(iconView(iconRes, tone.onTint, 22))
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 16f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    setTextColor(textPrimary)
                })
                addView(body(description).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(iconView(R.drawable.ic_chevron_right, muted, 22).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
            })
        })
    }

    private fun heroBanner(
        title: String,
        message: String,
        eyebrow: String,
        tone: Tone,
        iconRes: Int,
        compact: Boolean
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToOutline = true
        background = roundedDrawable(tone.tint, tone.border, 22f)
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(if (compact) 16 else 18), dp(18), dp(if (compact) 16 else 18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) }
        addView(LinearLayout(this@MainActivity).apply {
            background = roundedDrawable(cardBackground, cardBackground, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(if (compact) 10 else 12), dp(if (compact) 10 else 12), dp(if (compact) 10 else 12), dp(if (compact) 10 else 12))
            addView(iconView(iconRes, tone.onTint, if (compact) 22 else 24))
        }, LinearLayout.LayoutParams(dp(if (compact) 46 else 52), dp(if (compact) 46 else 52)).apply {
            marginEnd = dp(14)
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = eyebrow.uppercase(Locale.getDefault())
                textSize = 11.5f
                letterSpacing = 0.12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(tone.onTint)
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = if (compact) 21f else 23f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(tone.onTint)
                setPadding(0, dp(4), 0, 0)
            })
            if (message.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = message
                    textSize = 14.5f
                    setTextColor(tone.onTint)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(6), 0, 0)
                })
            }
        })
    }

    private fun addBack(parent: LinearLayout) {
        parent.addView(secondaryButton("Back").apply { setOnClickListener { navigateBack() } })
    }

    private fun navigateBack(fallback: (() -> Unit)? = backAction): Boolean {
        val previous = navigationHistory.removeLastOrNull() ?: return if (fallback != null) {
            fallback()
            true
        } else {
            false
        }
        displayedScreen?.let { forwardHistory += it }
        displaySnapshot(previous)
        return true
    }

    private fun addUrlButton(parent: LinearLayout, label: String, url: String) {
        if (url.isBlank()) return
        parent.addView(secondaryButton(label).apply {
            setOnClickListener { openAppLink(url, label.removePrefix("View ").removePrefix("Open ")) }
        })
    }

    // Keeps WBS website links inside the app: documents and images render natively,
    // known website pages map to the matching app screen, and anything else (such as
    // customer payment links) is offered through the share sheet instead of a browser.
    private fun openAppLink(rawUrl: String, title: String) {
        val url = normalizeExternalUrl(rawUrl)
        val uri = runCatching { url.toUri() }.getOrNull()
        val websiteHost = getString(R.string.website_url).toUri().host.orEmpty()
        if (uri == null || uri.host.isNullOrBlank()) {
            toast(getString(R.string.link_unavailable))
            return
        }
        if (!uri.host.equals(websiteHost, ignoreCase = true)) {
            openExternal(url)
            return
        }
        val path = uri.path.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
        val isImage = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any(path::endsWith)
        when {
            path.endsWith("/api/mobile/document.php") || path == "/invoice" ||
                path == "/payment-receipt-pdf" || path.endsWith(".pdf") || isImage ->
                showDocumentViewer(url, title)
            path == "/payment-receipt" ->
                showDocumentViewer(url.replaceFirst("/payment-receipt", "/payment-receipt-pdf"), title)
            path == "/admin/payments" -> loadPaymentsWorkspace(uri.getQueryParameter("account").orEmpty())
            path == "/admin/users" ->
                uri.getQueryParameter("edit_id")?.toIntOrNull()?.takeIf { it > 0 }
                    ?.let(::showAdminCustomer) ?: showCustomerManagement()
            path == "/bills" -> showBills()
            path == "/payments" -> showPayments()
            else -> shareLink(url, title)
        }
    }

    private fun shareLink(url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_link_title, title)))
        } catch (_: Exception) {
            toast(getString(R.string.link_unavailable))
        }
    }

    private fun addDocumentButton(parent: LinearLayout, label: String, url: String) {
        if (url.isBlank()) return
        parent.addView(secondaryButton(label).apply {
            setOnClickListener {
                showDocumentViewer(
                    normalizeExternalUrl(url),
                    label.removePrefix("View ").removePrefix("Open ")
                )
            }
        })
    }

    private fun showDocumentViewer(url: String, title: String) {
        val returnAction = backAction
        backAction = {
            backAction = returnAction
            returnAction?.invoke() ?: showDashboard()
        }
        showLoading("Opening $title")
        val loadingEpoch = screenEpoch
        api.download(url, cacheDir) { result ->
            runOnUiThread {
                if (loadingEpoch != screenEpoch) {
                    result.onSuccess { it.file.delete() }
                    return@runOnUiThread
                }
                result.onSuccess { document ->
                    try {
                        val isPdf = document.contentType.contains("pdf", ignoreCase = true) ||
                            url.substringBefore('?').endsWith(".pdf", ignoreCase = true) ||
                            FileInputStream(document.file).use { input ->
                                val header = ByteArray(5)
                                input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-"
                            }
                        when {
                            isPdf -> showPdfDocument(document.file, title)
                            document.contentType.startsWith("image/") ->
                                showImageDocument(document.file, title)
                            else -> {
                                // Never render website pages (e.g. a login redirect) inside the app.
                                document.file.delete()
                                handleError(IllegalStateException(getString(R.string.document_unavailable)))
                            }
                        }
                    } catch (error: Exception) {
                        document.file.delete()
                        handleError(error)
                    }
                }.onFailure(::handleError)
            }
        }
    }

    private fun showPdfDocument(file: File, title: String) {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            file.delete()
            handleError(error)
            return
        }
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            file.delete()
            handleError(error)
            return
        }
        file.delete()
        if (renderer.pageCount == 0) {
            renderer.close()
            descriptor.close()
            file.delete()
            handleError(IllegalStateException("This PDF does not contain any pages."))
            return
        }
        val form = screen(title, "${renderer.pageCount} page(s)")
        val pageLabel = body("")
        val pageImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            contentDescription = "$title page"
        }
        val previous = secondaryButton("Previous")
        val next = actionButton("Next")
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        form.addView(controls)
        controls.addView(previous, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER
        })
        controls.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(pageImage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(18) })
        var pageIndex = 0
        fun renderPage() {
            renderer.openPage(pageIndex).use { page ->
                val targetWidth = (resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(1)
                val targetHeight = (targetWidth * page.height / page.width).coerceAtLeast(1)
                val bitmap = createBitmap(targetWidth, targetHeight)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pageImage.setImageBitmap(bitmap)
                pageLabel.text = getString(R.string.pdf_page_count, pageIndex + 1, renderer.pageCount)
            }
            previous.isEnabled = pageIndex > 0
            next.isEnabled = pageIndex < renderer.pageCount - 1
        }
        previous.setOnClickListener { if (pageIndex > 0) { pageIndex--; renderPage() } }
        next.setOnClickListener { if (pageIndex < renderer.pageCount - 1) { pageIndex++; renderPage() } }
        renderPage()
        show(form)
        activePdfRenderer = renderer
        activePdfDescriptor = descriptor
    }

    private fun showImageDocument(file: File, title: String) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        file.delete()
        if (bitmap == null) {
            handleError(IllegalStateException("Could not display this image document."))
            return
        }
        val form = screen(title, "")
        form.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = title
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addBack(form)
        show(form)
    }

    private fun closeActivePdf() {
        activePdfRenderer?.close()
        activePdfRenderer = null
        activePdfDescriptor?.close()
        activePdfDescriptor = null
    }

    private fun addJsonRows(
        parent: LinearLayout,
        heading: String,
        rows: JSONArray?,
        fields: List<String>
    ) {
        if (rows == null || rows.length() == 0) return
        parent.addView(sectionTitle(heading))
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            parent.addView(card(
                row.optString(fields.first()).ifBlank { heading.removeSuffix("s") },
                fields.drop(1).joinToString(" • ") { row.optString(it) }.trim(),
                inferRecordTone(row)
            ))
        }
    }

    private fun addPaymentRows(
        parent: LinearLayout,
        payments: JSONArray?,
        openDetails: Boolean = false
    ) {
        if (payments == null || payments.length() == 0) {
            parent.addView(empty("No payments found."))
            return
        }
        parent.addView(sectionTitle("Payments"))
        for (index in 0 until payments.length()) {
            val payment = payments.optJSONObject(index) ?: continue
            val row = card(
                money(payment.optDouble("amount")),
                "${payment.optString("status")} • ${payment.optString("payment_method", "M-Pesa")}\n" +
                    listOf(payment.optString("mpesa_receipt"), payment.optString("transaction_date"))
                        .filter(String::isNotBlank).joinToString(" • "),
                inferRecordTone(payment, toneBlue)
            )
            if (openDetails && payment.optInt("id") > 0) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { showPayment(payment.optInt("id")) }
            }
            parent.addView(row)
        }
    }

    // Generic, schema-tolerant renderers used by the newer admin/staff workspaces below.
    // These format currency-looking keys and safely display nested objects/arrays
    // returned by endpoints whose exact response shape can evolve on the server.
    private fun formatValue(key: String, value: Any?): String {
        if (value == null || value == JSONObject.NULL) return "—"
        val text = value.toString()
        val lowerKey = key.lowercase()
        return if (lowerKey.contains("amount") || lowerKey.contains("balance") || lowerKey.contains("total")) {
            text.toDoubleOrNull()?.let { money(it) } ?: text
        } else text
    }

    private fun addRecordList(
        parent: LinearLayout,
        heading: String,
        rows: JSONArray?,
        titleFields: List<String> = listOf(
            "full_name", "title", "name", "subject", "entry_no", "reference_no",
            "account_number", "code", "period_key", "meter_number", "phone", "email", "memo"
        ),
        emptyState: String = "No records found.",
        showHeading: Boolean = true,
        onClick: ((JSONObject) -> Unit)? = null
    ) {
        if (showHeading) {
            parent.addView(sectionTitle(heading))
        }
        if (rows == null || rows.length() == 0) {
            parent.addView(empty(emptyState))
            return
        }
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val title = titleFields.map { row.optString(it) }.firstOrNull(String::isNotBlank)
                ?: (heading.removeSuffix("s") + " #" + (index + 1))
            val excluded = titleFields.toSet()
            val urlKeys = row.keys().asSequence().filter { isLinkField(it, row.opt(it)) }.sorted().toList()
            val scalarKeys = row.keys().asSequence()
                .filter { key ->
                    key !in excluded && key != "id" && key !in urlKeys &&
                        row.opt(key) !is JSONObject && row.opt(key) !is JSONArray
                }
                .sorted()
                .take(6)
                .toList()
            val nestedKeys = row.keys().asSequence()
                .filter { key -> key !in excluded && (row.opt(key) is JSONObject || row.opt(key) is JSONArray) }
                .sorted()
                .toList()
            val subtitle = scalarKeys.joinToString("\n") { key ->
                "${key.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }}: ${formatValue(key, row.opt(key))}"
            }
            val view = card(title, subtitle.ifBlank { "Tap to view details" }, inferRecordTone(row))
            if (onClick != null) {
                view.isClickable = true
                view.isFocusable = true
                view.setOnClickListener { onClick(row) }
            }
            parent.addView(view)
            urlKeys.forEach { key -> parent.addView(linkButton(key, row.optString(key), title)) }
            nestedKeys.forEach { key ->
                renderNode(
                    parent,
                    key.replace('_', ' ').replaceFirstChar { c -> c.uppercase() },
                    row.opt(key)
                )
            }
        }
    }

    private fun renderBudgetVsActual(parent: LinearLayout, rows: JSONArray) {
        if (rows.length() == 0) {
            parent.addView(empty("No budget accounts found for this year."))
            return
        }
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val accountTitle = listOf(row.optString("code"), row.optString("name"))
                .filter(String::isNotBlank)
                .joinToString(" • ")
                .ifBlank { "Budget account #${index + 1}" }
            parent.addView(card(
                accountTitle,
                row.optString("account_type").replace('_', ' ').replaceFirstChar(Char::uppercase),
                toneBlue
            ))
            parent.addView(summaryCardGrid(listOf(
                summaryCard("Budget total", money(row.optDouble("budget_total")), R.drawable.ic_wallet, toneBlue),
                summaryCard("Actual total", money(row.optDouble("actual_total")), R.drawable.ic_receipt, toneTeal),
                summaryCard(
                    "Variance",
                    money(row.optDouble("variance_total")),
                    R.drawable.ic_warning_triangle,
                    if (kotlin.math.abs(row.optDouble("variance_total")) > 0.009) toneAmber else toneTeal
                )
            )))
            addRecordList(
                parent,
                "Monthly breakdown",
                row.optJSONArray("months"),
                listOf("label")
            )
        }
    }

    private fun isLinkField(key: String, value: Any?): Boolean {
        if (!key.endsWith("_url")) return false
        val text = (value as? String)?.trim().orEmpty()
        return text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    }

    private fun linkButton(key: String, url: String, context: String): View {
        val name = key.removeSuffix("_url").removePrefix("public_").replace('_', ' ')
            .replace("pdf", "PDF").trim().ifBlank { "document" }
        val inApp = listOf("document", "receipt", "invoice", "pdf", "statement", "photo", "image")
            .any { key.contains(it, ignoreCase = true) }
        return secondaryButton(if (inApp) "Open $name" else "Share $name").apply {
            setOnClickListener {
                val display = name.replaceFirstChar(Char::uppercase)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (inApp) "Open $display?" else "Share $display?")
                    .setMessage(
                        if (inApp) "Do you want to open this $name for $context?"
                        else "Share this $name for $context?"
                    )
                    .setPositiveButton(if (inApp) "Open" else "Share") { _, _ -> openAppLink(url, display) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun renderNode(parent: LinearLayout, label: String, value: Any?) {
        when (value) {
            is JSONObject -> {
                if (value.length() == 0) return
                val scalarCards = mutableListOf<View>()
                val linkButtons = mutableListOf<View>()
                val nestedItems = mutableListOf<Pair<String, Any?>>()
                value.keys().asSequence().sorted().forEach { key ->
                    val child = value.opt(key)
                    val childLabel = key.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }
                    when {
                        isLinkField(key, child) -> linkButtons += linkButton(key, child?.toString().orEmpty(), label)
                        child is JSONObject || child is JSONArray -> nestedItems += childLabel to child
                        else -> if (child != null && child != JSONObject.NULL) {
                            scalarCards += summaryCard(
                                childLabel,
                                formatValue(key, child),
                                iconForSummaryKey(key),
                                toneForStatus(child.toString(), toneBlue)
                            )
                        }
                    }
                }
                if (scalarCards.isNotEmpty() || linkButtons.isNotEmpty()) {
                    parent.addView(sectionTitle(label))
                    if (scalarCards.isNotEmpty()) parent.addView(summaryCardGrid(scalarCards))
                    linkButtons.forEach(parent::addView)
                }
                nestedItems.forEach { (childLabel, child) -> renderNode(parent, childLabel, child) }
            }
            is JSONArray -> addRecordList(parent, label, value)
            else -> {}
        }
    }

    private fun toneForStatus(status: String, default: Tone = toneNeutral): Tone {
        val normalized = status.lowercase(Locale.getDefault())
        return when {
            normalized.contains("fail") || normalized.contains("error") || normalized.contains("reject") || normalized.contains("delete") -> toneRed
            normalized.contains("pending") || normalized.contains("overdue") || normalized.contains("warning") || normalized.contains("hold") || normalized.contains("inactive") || normalized.contains("suspend") -> toneAmber
            normalized.contains("paid") || normalized.contains("success") || normalized.contains("complete") || normalized.contains("active") || normalized.contains("sent") || normalized.contains("resolved") || normalized.contains("handled") -> toneTeal
            else -> default
        }
    }

    private fun inferRecordTone(row: JSONObject, default: Tone = toneNeutral): Tone {
        val statusKeys = listOf("status", "overall_label", "item_label", "approval_status")
        statusKeys.firstNotNullOfOrNull { key -> row.optString(key).takeIf(String::isNotBlank) }?.let {
            return toneForStatus(it, default)
        }
        val outstanding = row.optDouble("outstanding_amount", Double.NaN)
        if (!outstanding.isNaN()) {
            return if (outstanding > 0.01) toneAmber else toneTeal
        }
        return default
    }

    private fun toneForWorkspaceLabel(label: String): Tone {
        val normalized = label.lowercase(Locale.getDefault())
        return when {
            listOf("payment", "collection", "finance", "account", "bill", "invoice").any(normalized::contains) -> toneBlue
            listOf("approval", "notice", "onboarding", "support", "complaint").any(normalized::contains) -> toneAmber
            listOf("setting", "system", "activity", "log", "integration").any(normalized::contains) -> toneTeal
            else -> toneBlue
        }
    }

    private fun iconForWorkspaceLabel(label: String): Int {
        val normalized = label.lowercase(Locale.getDefault())
        return when {
            listOf("customer", "staff", "profile", "complaint").any(normalized::contains) -> R.drawable.ic_person
            listOf("payment", "collection", "budget", "transfer").any(normalized::contains) -> R.drawable.ic_wallet
            listOf("bill", "invoice", "report", "ledger", "statement", "receipt").any(normalized::contains) -> R.drawable.ic_receipt
            listOf("meter", "reading").any(normalized::contains) -> R.drawable.ic_meter
            listOf("support", "blog").any(normalized::contains) -> R.drawable.ic_support
            listOf("system", "setting", "activity", "log", "permission").any(normalized::contains) -> R.drawable.ic_admin
            else -> R.drawable.ic_circle_check
        }
    }

    private fun postAction(path: String, body: JSONObject, onSuccess: () -> Unit) {
        api.request(path, "POST", body) { result ->
            runOnUiThread {
                result.onSuccess {
                    toast(it.optString("message", "Updated."))
                    onSuccess()
                }.onFailure(::handleError)
            }
        }
    }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        buttons.forEachIndexed { index, (label, action) ->
            addView(secondaryButton(label).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                    if (index < buttons.size - 1) marginEnd = dp(6)
                    bottomMargin = 0
                }
                setOnClickListener { action() }
            })
        }
    }

    private fun performSignOut() {
        api.request("logout.php", "POST") {
            runOnUiThread { clearSession() }
        }
    }

    private fun signOutButton() = secondaryButton("Sign out").apply {
        setOnClickListener { performSignOut() }
    }

    private fun clearSession() {
        navigationHistory.clear()
        forwardHistory.clear()
        displayedScreen = null
        token = null
        currentUser = null
        preferences.edit { remove("access_token") }
        showLogin()
    }

    private fun JSONObject.data(): JSONObject = optJSONObject("data") ?: JSONObject()

    private fun onResult(
        result: Result<JSONObject>,
        fallback: String,
        success: (JSONObject) -> Unit
    ) {
        runOnUiThread {
            result.onSuccess(success).onFailure {
                handleError(if (it.message.isNullOrBlank()) IllegalStateException(fallback) else it)
            }
        }
    }

    private fun handleError(error: Throwable) {
        when (error) {
            is ApiException -> when (error.statusCode) {
                401 -> if (token != null) {
                    toast("Your session has expired. Please sign in again.")
                    clearSession()
                } else {
                    toast(error.message ?: "Something went wrong.")
                }
                403 -> {
                    toast("Your account does not have permission to use this function.")
                    showDashboard()
                }
                else -> toast(error.message ?: "Something went wrong.")
            }
            else -> toast(error.message ?: "Something went wrong.")
        }
    }

    private fun setLoading(button: Button, loading: Boolean, label: String) {
        button.isEnabled = !loading
        button.text = if (loading) "Please wait..." else label
        button.alpha = if (loading) 0.65f else 1f
    }

    private fun money(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-KE")).format(amount)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun roundedDrawable(fill: Int, stroke: Int, radiusDp: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        closeActivePdf()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()    }
}
