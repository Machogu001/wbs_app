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
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
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
        val backAction: (() -> Unit)?
    )
    private val navigationHistory = mutableListOf<ScreenSnapshot>()
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
    private val pageBackground by lazy { ContextCompat.getColor(this, R.color.page_background) }
    private val cardBackground by lazy { ContextCompat.getColor(this, R.color.surface_card) }
    private val cardBackgroundMuted by lazy { ContextCompat.getColor(this, R.color.surface_card_muted) }
    private val textPrimary by lazy { ContextCompat.getColor(this, R.color.body_text) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.muted_text) }
    private val border by lazy { ContextCompat.getColor(this, R.color.border) }
    private val tintBlue by lazy { ContextCompat.getColor(this, R.color.surface_tint_blue) }
    private val tintTeal by lazy { ContextCompat.getColor(this, R.color.surface_tint_teal) }
    private val tintAmber by lazy { ContextCompat.getColor(this, R.color.surface_tint_amber) }
    private val tintRed by lazy { ContextCompat.getColor(this, R.color.surface_tint_red) }
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
        form.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = getString(R.string.app_name)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(124)
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
        })
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
            addUrlButton(form, "Open payment page", it.optString("public_payment_url"))
            addDocumentButton(form, "Open document", it.optString("document_url"))
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
        val user = currentUser
        val name = user?.optString("full_name")?.takeIf(String::isNotBlank) ?: "customer"
        val form = screen("My Water Bill", "Welcome back")
        form.addView(identityCard(
            name,
            user?.optString("account_number").orEmpty(),
            user?.optString("role", "customer").orEmpty().replaceFirstChar(Char::uppercase)
        ))
        form.addView(sectionTitle("Account summary"))
        form.addView(summaryCardGrid(listOf(
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
        )))
        form.addView(sectionTitle("Quick actions"))
        form.addView(quickActionGrid(listOf(
            Triple("Profile", R.drawable.ic_person, ::showProfile),
            Triple("Statement", R.drawable.ic_receipt, ::showStatement),
            Triple("Bills", R.drawable.ic_wallet) { showBills() },
            Triple("Payments", R.drawable.ic_circle_check, ::showPayments),
            Triple("Meters", R.drawable.ic_meter, ::showMeters),
            Triple("Readings", R.drawable.ic_circle_clock, ::showReadings),
            Triple("Submit reading", R.drawable.ic_meter, ::showSubmitReading),
            Triple("Complaints", R.drawable.ic_support, ::showComplaints)
        )))
        if (user?.optString("role", "customer")?.lowercase() != "customer") {
            form.addView(sectionTitle("Staff workspaces"))
            addWorkspace(form, "Customers & service", "Customer records, onboarding, locations and complaints", R.drawable.ic_group, ::showCustomersWorkspace)
            addWorkspace(form, "Billing & payments", "Collections, invoicing, corrections and payment operations", R.drawable.ic_receipt, ::showBillingWorkspace)
            addWorkspace(form, "Finance & accounting", "Approvals, reports, ledgers, budgets and transfers", R.drawable.ic_wallet, ::showFinanceWorkspace)
            addWorkspace(form, "Operations & support", "Demand notices, inquiries, integrations and publishing", R.drawable.ic_support, ::showOperationsWorkspace)
            addWorkspace(form, "System administration", "Staff access, permissions, settings and audit logs", R.drawable.ic_admin, ::showSystemWorkspace)
        }
        form.addView(signOutButton())
        show(form)
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
            "Collections" to ::showCollections,
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
        destinations.forEach { (label, action) -> addMenu(form, label, action) }
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
                        renderProfile(user, serverPreference, availablePreferences)
                    }
                }
            }
        }
    }

    private fun renderProfile(user: JSONObject, themePreference: String, availablePreferences: List<String>) {
        val form = screen("Profile", user.optString("full_name"))
        addField(form, "Account", user.optString("account_number"))
        addField(form, "Username", user.optString("username"))
        addField(form, "Role", user.optString("role"))
        addField(form, "Phone", user.optString("phone_number"))
        addField(form, "Email", user.optString("email"))
        addField(form, "Address", user.optString("address"))
        addField(form, "Connection type", user.optString("connection_type"))
        addField(form, "Status", user.optString("status"))
        form.addView(sectionTitle("Appearance"))
        form.addView(body(
            "Choose how My Water Bill looks on this device. Current: ${themePreferenceLabel(themePreference)}."
        ).apply { setPadding(dp(2), 0, 0, dp(10)) })
        form.addView(optionGroup(
            "Theme",
            availablePreferences.map { it to themePreferenceLabel(it) },
            themePreference,
            1
        ) { selected -> updateThemePreference(selected, user) })
        val edit = actionButton("Edit profile")
        edit.setOnClickListener { showEditProfile(user) }
        form.addView(edit)
        val password = secondaryButton("Change password")
        password.setOnClickListener { showChangePassword() }
        form.addView(password)
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
                    val form = screen("Account statement", "Bills and completed payments")
                    form.addView(card("Billed", money(summary.optDouble("billed_amount"))))
                    form.addView(card("Paid", money(summary.optDouble("paid_amount"))))
                    form.addView(card("Outstanding", money(summary.optDouble("outstanding_amount"))))
                    val bills = data.optJSONArray("bills") ?: JSONArray()
                    if (bills.length() > 0) {
                        form.addView(sectionTitle("Bills"))
                        for (index in 0 until bills.length()) {
                            val bill = bills.optJSONObject(index) ?: continue
                            val row = actionButton(
                                "${bill.optString("billing_month")} • ${money(bill.optDouble("amount"))}\n" +
                                    bill.optString("status")
                            )
                            row.isAllCaps = false
                            row.setOnClickListener { showBill(bill.optInt("id")) }
                            form.addView(row)
                        }
                    }
                    addPaymentRows(form, data.optJSONArray("payments"), true)
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
            listOf(subject, message, submit, list).forEach(form::addView)
            addBack(form)
            fun load() {
                api.request("complaints.php") { result ->
                    runOnUiThread {
                        result.onSuccess { response ->
                            list.removeAllViews()
                            val rows = response.data().optJSONArray("complaints") ?: JSONArray()
                            if (rows.length() == 0) list.addView(empty("No complaints submitted."))
                            for (index in 0 until rows.length()) {
                                val row = rows.optJSONObject(index) ?: continue
                                list.addView(card(
                                    row.optString("subject"),
                                    "${row.optString("status")} • ${row.optString("created_at")}\n${row.optString("message")}"
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

    private fun showBills(status: String = "") {
        childScreen()
        showLoading("Loading bills")
        val path = api.query("bills.php", mapOf("limit" to "100", "status" to status))
        api.request(path) { result ->
            onResult(result, "Could not load bills") { response ->
                val data = response.data()
                val bills = data.optJSONArray("bills") ?: JSONArray()
                val form = screen("Bills", "${data.optInt("total", bills.length())} bill(s)")
                if (bills.length() == 0) form.addView(empty("No bills found."))
                for (index in 0 until bills.length()) {
                    val bill = bills.optJSONObject(index) ?: continue
                    val view = actionButton(
                        "${bill.optString("type_label", "Bill")} • ${bill.optString("billing_month")}\n" +
                            "${money(bill.optDouble("outstanding_amount"))} due • ${bill.optString("status")}"
                    )
                    view.isAllCaps = false
                    view.setOnClickListener { showBill(bill.optInt("id")) }
                    form.addView(view)
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
                addField(form, "Account", bill.optString("account_number"))
                addField(form, "Status", bill.optString("status"))
                addField(form, "Previous reading", bill.optString("previous_reading"))
                addField(form, "Current reading", bill.optString("current_reading"))
                addField(form, "Consumption", bill.optString("consumption"))
                addField(form, "Total", money(bill.optDouble("amount")))
                addField(form, "Paid", money(bill.optDouble("paid_amount")))
                addField(form, "Outstanding", money(bill.optDouble("outstanding_amount")))
                addField(form, "Due date", bill.optString("due_date"))
                if (bill.optDouble("outstanding_amount") > 0.01) {
                    val pay = actionButton("Pay with M-Pesa")
                    pay.setOnClickListener { showPaymentForm(id) }
                    form.addView(pay)
                }
                addUrlButton(form, "Open payment page", bill.optString("public_payment_url"))
                addDocumentButton(form, "Open invoice", bill.optString("document_url"))
                if (bill.optDouble("outstanding_amount") > 0.01) {
                    val request = secondaryButton("Request installment, waiver or write-off")
                    request.setOnClickListener { showBillAction(id, bill.optDouble("outstanding_amount")) }
                    form.addView(request)
                }
                addJsonRows(form, "Line items", bill.optJSONArray("line_items"), listOf("description", "line_type", "amount"))
                addPaymentRows(form, bill.optJSONArray("payments"))
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
        addField(form, "Amount", money(payment.optDouble("amount")))
        addField(form, "Outstanding balance", money(payment.optDouble("outstanding_amount")))
        addField(form, "Phone", payment.optString("phone_number"))
        addField(form, "M-Pesa receipt", payment.optString("mpesa_receipt").ifBlank { "Pending" })
        addField(form, "Transaction date", payment.optString("transaction_date").ifBlank { "—" })
        if (isFinal && isSuccessful) {
            addDocumentButton(form, "View receipt", payment.optString("receipt_url"))
            addDocumentButton(form, "View receipt PDF", payment.optString("receipt_pdf_url"))
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
        form.addView(refresh)
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
        val retry = actionButton("Try again")
        retry.setOnClickListener {
            setLoading(retry, true, "Try again")
            fetchPaymentStatus(screenEpoch, billId, paymentId, checkoutRequestId, isManualRefresh = true)
        }
        form.addView(retry)
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
                addPaymentRows(form, payments, true)
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
                addField(form, "Amount", money(payment.optDouble("amount")))
                addField(form, "Status", payment.optString("status"))
                addField(form, "Method", payment.optString("payment_method"))
                addField(form, "Phone", payment.optString("phone_number"))
                addField(form, "Account", payment.optString("account_number"))
                addField(form, "Transaction date", payment.optString("transaction_date"))
                addDocumentButton(form, "View receipt", payment.optString("receipt_url"))
                addDocumentButton(form, "View receipt PDF", payment.optString("receipt_pdf_url"))
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
                if (meters.length() == 0) form.addView(empty("No meters are linked to this account."))
                for (index in 0 until meters.length()) {
                    val meter = meters.optJSONObject(index) ?: continue
                    form.addView(card(
                        meter.optString("meter_number"),
                        listOf(
                            meter.optString("meter_label"),
                            meter.optString("status"),
                            if (meter.optBoolean("is_primary")) "Primary meter" else ""
                        ).filter(String::isNotBlank).joinToString(" • ")
                    ))
                }
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
                if (readings.length() == 0) form.addView(empty("No meter readings submitted."))
                for (index in 0 until readings.length()) {
                    val reading = readings.optJSONObject(index) ?: continue
                    form.addView(card(
                        "${reading.optString("meter_number")} • ${reading.optString("current_reading")}",
                        "${reading.optString("billing_month")} • ${reading.optString("status")}"
                    ))
                    addUrlButton(form, "View meter photo", reading.optString("photo_url"))
                }
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
        val billingMonth = input("Billing month YYYY-MM-DD (optional)")
        val dueDate = input("Due date YYYY-MM-DD (optional)")
        readingInputs = listOf(reading, meter, billingMonth, dueDate)
        val choosePhoto = secondaryButton("Choose meter photo")
        readingPhotoLabel = body("No photo selected")
        val submit = actionButton("Submit reading")
        val form = screen("Submit meter reading", "A clear JPG or PNG meter photo is required.")
        readingInputs.forEach(form::addView)
        form.addView(choosePhoto)
        form.addView(readingPhotoLabel)
        form.addView(submit)
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
        form.addView(query)
        form.addView(search)
        form.addView(results)
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
                            val row = actionButton(
                                "${client.optString("full_name")}\n" +
                                    "${client.optString("account_number")} • ${client.optString("status")}"
                            )
                            row.isAllCaps = false
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
                addField(form, "Status", customer.optString("status"))
                addField(form, "Role", customer.optString("role"))
                addField(form, "Phone", customer.optString("phone_number"))
                addField(form, "Email", customer.optString("email"))
                addField(form, "Outstanding", money(summary.optDouble("outstanding_amount")))
                val bills = data.optJSONArray("recent_bills") ?: JSONArray()
                if (bills.length() > 0) {
                    form.addView(sectionTitle("Recent bills"))
                    for (index in 0 until bills.length()) {
                        val bill = bills.optJSONObject(index) ?: continue
                        val row = actionButton(
                            "${bill.optString("billing_month")} • ${money(bill.optDouble("outstanding_amount"))} due"
                        )
                        row.isAllCaps = false
                        row.setOnClickListener { showAdminBill(bill.optInt("id"), id) }
                        form.addView(row)
                    }
                }
                addPaymentRows(form, data.optJSONArray("recent_payments"))
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
                addField(form, "Account", bill.optString("account_number"))
                addField(form, "Month", bill.optString("billing_month"))
                addField(form, "Status", bill.optString("status"))
                addField(form, "Amount", money(bill.optDouble("amount")))
                addField(form, "Outstanding", money(bill.optDouble("outstanding_amount")))
                addJsonRows(form, "Line items", bill.optJSONArray("line_items"), listOf("description", "line_type", "amount"))
                addPaymentRows(form, bill.optJSONArray("payments"))
                addJsonRows(form, "Credit notes", bill.optJSONArray("credit_notes"), listOf("type", "amount_credited", "note"))
                addJsonRows(form, "Approval items", bill.optJSONArray("approval_items"), listOf("title", "status", "amount"))
                addDocumentButton(form, "Open invoice", bill.optString("document_url"))
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
                if (summary != null) {
                    summary.keys().forEach { key -> addField(form, key.replace('_', ' '), summary.optString(key)) }
                }
                val items = data.optJSONArray("items") ?: JSONArray()
                if (items.length() == 0) form.addView(empty("No approval items found."))
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    form.addView(card(
                        item.optString("title", item.optString("reference_no", "Approval")),
                        "${item.optString("status")} • ${money(item.optDouble("amount"))}"
                    ))
                    if (item.optString("status").lowercase() == "pending") {
                        val approve = actionButton("Approve")
                        approve.setOnClickListener { decideApproval(item.optInt("id"), "approve") }
                        form.addView(approve)
                        val reject = secondaryButton("Reject")
                        reject.setOnClickListener { decideApproval(item.optInt("id"), "reject") }
                        form.addView(reject)
                    }
                }
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

    private fun showCollections() {
        childScreen()
        showLoading("Loading collections")
        api.request("admin/collections.php?days=30&limit=100") { result ->
            onResult(result, "Could not load collections") { response ->
                val data = response.data()
                val summary = data.optJSONObject("summary") ?: JSONObject()
                val form = screen("Collections", "Last ${data.optInt("period_days", 30)} days")
                form.addView(card("Completed", money(summary.optDouble("completed_collected"))))
                form.addView(card("Pending", money(summary.optDouble("pending_collected"))))
                form.addView(card("Failed", money(summary.optDouble("failed_collected"))))
                form.addView(card("Customers served", summary.optInt("customers_served").toString()))
                addJsonRows(
                    form,
                    "By payment method",
                    data.optJSONArray("by_payment_method"),
                    listOf("payment_method", "payment_count", "total_amount")
                )
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
                    addUrlButton(form, "Open record", record.optString("open_url"))
                    addUrlButton(form, "Open document", record.optString("document_url"))
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
                form.addView(sectionTitle("Error logs (${errors.length()})"))
                if (errors.length() == 0) form.addView(empty("No error logs found."))
                for (index in 0 until errors.length()) {
                    val err = errors.optJSONObject(index) ?: continue
                    val id = err.optInt("id")
                    form.addView(card(err.optString("service", "Error"), "${err.optString("message")}\n${err.optString("created_at")}"))
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
                val smsQueue = data.optJSONObject("sms_queue") ?: JSONObject()
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
                renderFinanceSummary(form, data.optJSONObject("summary"))
                val accounts = data.optJSONArray("accounts") ?: JSONArray()
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
                        "${account.optString("account_type").replace('_', ' ').replaceFirstChar(Char::uppercase)} • ${if (active) "Active" else "Inactive"}",
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
                form.addView(sectionTitle("Financial statements"))
                renderFinanceNode(form, "Balance sheet", data.opt("balance_sheet"))
                renderFinanceNode(form, "Profit and loss", data.opt("profit_and_loss"))
                form.addView(sectionTitle("Cash & receivables"))
                renderFinanceNode(form, "Cash flow", data.opt("cash_flow"))
                renderFinanceNode(form, "Accounts receivable aging", data.opt("accounts_receivable_aging"))
                show(form)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Accounting ledger
    // ---------------------------------------------------------------------

    private fun showAccountingLedger() {
        childScreen()
        loadAccountingLedger(0, 0)
    }

    private fun loadAccountingLedger(accountId: Int, entryId: Int) {
        showLoading("Loading accounting ledger")
        val params = mutableMapOf<String, String>()
        if (accountId > 0) params["account_id"] = accountId.toString()
        if (entryId > 0) params["entry_id"] = entryId.toString()
        val path = if (params.isEmpty()) "admin/accounting_ledger.php" else api.query("admin/accounting_ledger.php", params)
        api.request(path) { result ->
            onResult(result, "Could not load accounting ledger") { response ->
                val data = response.data()
                val form = screen("Accounting ledger", "Accounts, ledgers and journal entries")
                val accounts = data.optJSONArray("accounts") ?: JSONArray()
                form.addView(sectionTitle("Find account activity"))
                val accountChoices = accountOptions(accounts)
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
                            loadAccountingLedger(selectedId, 0)
                        }
                    }
                })
                val selectedAccount = data.optJSONObject("selected_account")
                if (selectedAccount != null) {
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
                    row.setOnClickListener { childScreen(); loadAccountingLedger(accountId, id) }
                    form.addView(row)
                }
                val selectedEntry = data.optJSONObject("selected_entry")
                if (selectedEntry != null) {
                    form.addView(sectionTitle("Entry detail"))
                    addField(form, "Entry no", selectedEntry.optString("entry_no"))
                    addField(form, "Status", selectedEntry.optString("status"))
                    addField(form, "Memo", selectedEntry.optString("memo"))
                    addRecordList(form, "Lines", selectedEntry.optJSONArray("lines"), listOf("account_name", "line_memo"))
                    if (selectedEntry.optString("status") == "posted") {
                        val reverse = secondaryButton("Reverse this entry")
                        val id = selectedEntry.optInt("id")
                        reverse.setOnClickListener {
                            postAction(
                                "admin/accounting_ledger.php",
                                JSONObject().put("action", "reverse_entry").put("entry_id", id).put("reversal_date", today())
                            ) { showAccountingLedger() }
                        }
                        form.addView(reverse)
                    }
                }
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
                form.addView(sectionTitle("Budget performance"))
                renderFinanceNode(form, "Budget vs actual", data.opt("budget_vs_actual"))
                form.addView(sectionTitle("Set a yearly budget"))
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
                form.addView(sectionTitle("New transfer"))
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
                val (searchBox, search) = searchableIdentifierField("Search by name, account or meter", query) { item ->
                    val value = item.optString("selection_value").ifBlank { item.optString("account_number") }
                    childScreen(); loadCustomerManagement(value)
                }
                val searchBtn = actionButton("Search")
                form.addView(searchBox)
                form.addView(searchBtn)
                searchBtn.setOnClickListener { childScreen(); loadCustomerManagement(search.text.toString().trim()) }
                val create = actionButton("New customer")
                create.setOnClickListener { showCustomerCreateForm(registrationFee) }
                form.addView(create)
                val users = data.optJSONArray("users") ?: JSONArray()
                form.addView(sectionTitle("Customers (${users.length()})"))
                if (users.length() == 0) form.addView(empty("No customers found."))
                for (index in 0 until users.length()) {
                    val user = users.optJSONObject(index) ?: continue
                    val row = card(
                        "${user.optString("full_name")} • ${user.optString("account_number")}",
                        "${user.optString("status")} • ${user.optString("phone_number")}\nMeters: ${user.optString("meter_numbers")}"
                    )
                    row.isClickable = true
                    row.isFocusable = true
                    val id = user.optInt("id")
                    row.setOnClickListener { showCustomerEditForm(id, registrationFee) }
                    form.addView(row)
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showCustomerCreateForm(registrationFee: Double) {
        backAction = { showCustomerManagement() }
        val firstName = input("First name")
        val middleName = input("Middle name (optional)")
        val lastName = input("Last name")
        val phoneCode = input("Phone country code").apply { setText(R.string.phone_country_code_default) }
        val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE)
        val email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val idNumber = input("ID number")
        val address = input("Address")
        val taxPin = input("Tax PIN (optional)")
        val connectionType = input("Connection type: domestic, commercial or industrial").apply { setText(R.string.connection_type_domestic) }
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
        listOf(
            firstName, middleName, lastName, phoneCode, phoneLocal, email, idNumber, address, taxPin,
            connectionType, unitRate, password, alreadyPaid, paidAmount, mpesaCode, sendStk, create
        ).forEach(form::addView)
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
                .put("phone_country_code", phoneCode.text.toString().trim())
                .put("phone_number_local", phoneLocal.text.toString().trim())
                .put("email", email.text.toString().trim())
                .put("id_number", idNumber.text.toString().trim())
                .put("address", address.text.toString().trim())
                .put("tax_pin", taxPin.text.toString().trim())
                .put("connection_type", connectionType.text.toString().trim().lowercase())
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
                val user = data.optJSONObject("edit_user") ?: JSONObject()
                val nameParts = user.optString("full_name").trim().split(Regex("\\s+")).filter(String::isNotBlank)
                val firstName = input("First name").apply { setText(nameParts.getOrNull(0) ?: "") }
                val middleName = input("Middle name (optional)").apply {
                    setText(if (nameParts.size > 2) nameParts.subList(1, nameParts.size - 1).joinToString(" ") else "")
                }
                val lastName = input("Last name").apply { setText(if (nameParts.size > 1) nameParts.last() else "") }
                val phoneCode = input("Phone country code").apply { setText(R.string.phone_country_code_default) }
                val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE).apply {
                    setText(user.optString("phone_number").removePrefix("254"))
                }
                val email = input("Email").apply { setText(user.optString("email")) }
                val idNumber = input("ID number").apply { setText(user.optString("id_number")) }
                val address = input("Address").apply { setText(user.optString("address")) }
                val taxPin = input("Tax PIN (optional)").apply { setText(user.optString("tax_pin")) }
                val meterNumber = input("Primary meter number").apply { setText(user.optString("meter_number")) }
                val connectionType = input("Connection type").apply {
                    setText(user.optString("connection_type").takeIf(String::isNotBlank) ?: "domestic")
                }
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
                listOf(
                    firstName, middleName, lastName, phoneCode, phoneLocal, email, idNumber, address, taxPin,
                    meterNumber, connectionType, unitRate, locationLabel, latitude, longitude, newPassword, save
                ).forEach(form::addView)
                addBack(form)
                form.addView(buttonRow(
                    "Activate" to { postAction("admin/users.php", JSONObject().put("form_type", "update_status").put("user_id", userId).put("new_status", "active")) { showCustomerManagement() } },
                    "Suspend" to { postAction("admin/users.php", JSONObject().put("form_type", "update_status").put("user_id", userId).put("new_status", "suspended")) { showCustomerManagement() } }
                ))
                form.addView(sectionTitle("Add another meter"))
                val extraMeterNumber = input("Additional meter number")
                val extraMeterLabel = input("Meter label (optional)")
                val addMeter = secondaryButton("Add additional meter")
                form.addView(extraMeterNumber)
                form.addView(extraMeterLabel)
                form.addView(addMeter)
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
                form.addView(sectionTitle("Meters on this account"))
                val meters = data.optJSONArray("edit_user_meters") ?: JSONArray()
                if (meters.length() == 0) form.addView(empty("No meters recorded for this customer."))
                for (index in 0 until meters.length()) {
                    val meter = meters.optJSONObject(index) ?: continue
                    val meterStatus = meter.optString("status", "active")
                    form.addView(card(
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
                        form.addView(replace)
                    }
                }
                addRecordList(
                    form,
                    "Meter replacement history",
                    data.optJSONArray("edit_user_meter_replacements"),
                    listOf("replacement_meter_number", "meter_number", "old_meter_number")
                )
                if (user.optString("status") != "active") {
                    val resendStk = secondaryButton("Resend registration STK push")
                    resendStk.setOnClickListener {
                        postAction("admin/users.php", JSONObject().put("form_type", "resend_registration_stk").put("user_id", userId)) {
                            showCustomerEditForm(userId, registrationFee)
                        }
                    }
                    form.addView(resendStk)
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
                form.addView(delete)
                save.setOnClickListener {
                    val body = JSONObject()
                        .put("form_type", "edit_user_save")
                        .put("user_id", userId)
                        .put("first_name", firstName.text.toString().trim())
                        .put("middle_name", middleName.text.toString().trim())
                        .put("last_name", lastName.text.toString().trim())
                        .put("phone_country_code", phoneCode.text.toString().trim())
                        .put("phone_number_local", phoneLocal.text.toString().trim())
                        .put("email", email.text.toString().trim())
                        .put("id_number", idNumber.text.toString().trim())
                        .put("address", address.text.toString().trim())
                        .put("tax_pin", taxPin.text.toString().trim())
                        .put("meter_number", meterNumber.text.toString().trim())
                        .put("connection_type", connectionType.text.toString().trim().lowercase())
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
                create.setOnClickListener { showStaffEditor(null) }
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
                    row.setOnClickListener { showStaffEditor(user) }
                    form.addView(row)
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showStaffEditor(user: JSONObject?) {
        backAction = ::showStaffUsers
        val isNew = user == null
        val nameParts = user?.optString("full_name")?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: emptyList()
        val firstName = input("First name").apply { setText(nameParts.getOrNull(0) ?: "") }
        val middleName = input("Middle name (optional)").apply {
            setText(if (nameParts.size > 2) nameParts.subList(1, nameParts.size - 1).joinToString(" ") else "")
        }
        val lastName = input("Last name").apply { setText(if (nameParts.size > 1) nameParts.last() else "") }
        val username = input("Username").apply { setText(user?.optString("username") ?: "") }
        val phoneCode = input("Phone country code").apply { setText(R.string.phone_country_code_default) }
        val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE).apply {
            setText(user?.optString("phone_number")?.removePrefix("254") ?: "")
        }
        val idNumber = input("ID number").apply { setText(user?.optString("id_number") ?: "") }
        val role = input("Role: admin, reader, finance or support").apply { setText(user?.optString("role")?.takeIf(String::isNotBlank) ?: "reader") }
        val status = input("Status: active, inactive or suspended").apply { setText(user?.optString("status")?.takeIf(String::isNotBlank) ?: "active") }
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
                    .put("phone_country_code", phoneCode.text.toString().trim())
                    .put("phone_number_local", phoneLocal.text.toString().trim())
                    .put("id_number", idNumber.text.toString().trim())
                    .put("role", role.text.toString().trim().lowercase())
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
                    .put("phone_country_code", phoneCode.text.toString().trim())
                    .put("phone_number_local", phoneLocal.text.toString().trim())
                    .put("id_number", idNumber.text.toString().trim())
                    .put("role", role.text.toString().trim().lowercase())
                    .put("status", status.text.toString().trim().lowercase())
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
        val name = input("Tariff name")
        val category = input("Category: all, domestic, commercial or industrial").apply { setText(R.string.tariff_category_all) }
        val effectiveFrom = input("Effective from YYYY-MM-DD").apply { setText(today()) }
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
                    .put("tariff_category", category.text.toString().trim().lowercase())
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

    private fun showTermsConditions() {
        childScreen()
        showLoading("Loading terms and conditions")
        api.request("admin/terms_conditions.php") { result ->
            onResult(result, "Could not load terms and conditions") { response ->
                val data = response.data()
                val termsText = data.optString("terms_conditions_content")
                val form = screen("Terms & Conditions", "Customer terms and conditions")
                val sections = data.optJSONArray("rendered_terms_sections")
                if (sections != null && sections.length() > 0) {
                    for (index in 0 until sections.length()) {
                        val section = sections.optJSONObject(index) ?: continue
                        val heading = section.optString("title")
                        val text = section.optString("content")
                        if (heading.isBlank() && text.isBlank()) continue
                        form.addView(card(heading.ifBlank { "Terms & Conditions" }, text))
                    }
                } else if (termsText.isNotBlank()) {
                    val formattedTerms = TextView(this).apply {
                        text = Html.fromHtml(
                            data.optString("rendered_terms").ifBlank { termsText },
                            Html.FROM_HTML_MODE_COMPACT
                        )
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
                } else {
                    form.addView(empty("Terms and conditions have not been added yet."))
                }
                val edit = secondaryButton("Edit terms and conditions")
                form.addView(edit)
                addBack(form)
                edit.setOnClickListener {
                    val content = multilineInput("Write terms and conditions").apply {
                        setText(termsText)
                        minLines = 8
                    }
                    val password = input(
                        "Confirm your password to save",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    )
                    val editor = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), 0)
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
                                        .put("terms_conditions_content", content.text.toString())
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
                val currentUser = data.optJSONObject("current_user")
                val form = screen("Payments workspace", "Search a customer to record payments and adjustments")
                val (searchBox, search) = searchableIdentifierField("Account, meter number or name", account) { item ->
                    val value = item.optString("selection_value").ifBlank { item.optString("account_number") }
                    childScreen(); loadPaymentsWorkspace(value)
                }
                val searchBtn = actionButton("Search account")
                form.addView(searchBox)
                form.addView(searchBtn)
                searchBtn.setOnClickListener { childScreen(); loadPaymentsWorkspace(search.text.toString().trim()) }
                if (currentUser != null) {
                    form.addView(card(currentUser.optString("full_name"), "${currentUser.optString("account_number")} • ${currentUser.optString("status")}"))
                    form.addView(card("Wallet balance", money(data.optDouble("wallet_balance"))))
                    val bills = data.optJSONArray("bills") ?: JSONArray()
                    form.addView(sectionTitle("Open bills"))
                    if (bills.length() == 0) form.addView(empty("No open bills."))
                    for (index in 0 until bills.length()) {
                        val bill = bills.optJSONObject(index) ?: continue
                        val billId = bill.optInt("id")
                        form.addView(card("${bill.optString("billing_month")} • ${money(bill.optDouble("outstanding_amount"))} due", bill.optString("status")))
                        form.addView(buttonRow(
                            "Record payment" to { showManualPaymentForm(currentUser.optString("account_number"), billId, "invoice") },
                            "Credit note" to { showCreditNoteForm(currentUser.optString("account_number"), billId) }
                        ))
                    }
                    val recordBalance = secondaryButton("Record payment to account balance")
                    recordBalance.setOnClickListener { showManualPaymentForm(currentUser.optString("account_number"), 0, "balance") }
                    form.addView(recordBalance)
                    val payments = data.optJSONArray("payments") ?: JSONArray()
                    form.addView(sectionTitle("Completed payments"))
                    if (payments.length() == 0) form.addView(empty("No completed payments."))
                    for (index in 0 until payments.length()) {
                        val payment = payments.optJSONObject(index) ?: continue
                        val paymentId = payment.optInt("id")
                        form.addView(card(money(payment.optDouble("amount")), "${payment.optString("status")} • ${payment.optString("transaction_date")}"))
                        val adjust = secondaryButton("Request adjustment")
                        adjust.setOnClickListener { showPaymentAdjustmentForm(currentUser.optString("account_number"), paymentId) }
                        form.addView(adjust)
                    }
                    addRecordList(form, "Adjustment requests", data.optJSONArray("payment_adjustments"), listOf("adjustment_type", "status"))
                }
                addBack(form)
                show(form)
            }
        }
    }

    private fun showManualPaymentForm(accountNumber: String, billId: Int, target: String) {
        backAction = { showPaymentsWorkspace() }
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
            "cash"
        )
        val reference = input("Payment reference")
        val paidDate = datePickerInput("Paid date", today())
        val paidTime = input("Paid time HH:MM (optional)")
        val phone = input("Phone number (optional)", InputType.TYPE_CLASS_PHONE)
        val note = multilineInput("Note (optional)")
        val save = actionButton("Record payment")
        val form = screen("Record payment", "$accountNumber • target: $target")
        listOf(amount, method, reference, paidDate, paidTime, phone, note, save).forEach(form::addView)
        addBack(form)
        save.setOnClickListener {
            if (amount.text.isBlank() || paidDate.text.isBlank()) {
                toast("Enter the amount and paid date.")
                return@setOnClickListener
            }
            val body = JSONObject()
                .put("action", "manual_payment")
                .put("account_number", accountNumber)
                .put("bill_id", billId)
                .put("payment_target", target)
                .put("payment_method", method.tag as String)
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
        val type = input("Credit type: full or partial").apply { setText(R.string.credit_type_full) }
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
                    .put("credit_type", type.text.toString().trim().lowercase())
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
        val type = input("Adjustment type: refund or correction").apply { setText(R.string.adjustment_type_refund) }
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
                    .put("adjustment_type", type.text.toString().trim().lowercase())
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
                create.setOnClickListener { showCreateProforma(fee) }
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
                    addUrlButton(form, "Open share link", proforma.optString("share_url"))
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

    private fun showCreateProforma(fee: Double) {
        backAction = ::showRegistrationProformas
        val customerType = input("Customer type: individual or company").apply { setText(R.string.customer_type_individual) }
        val firstName = input("First name / contact first name")
        val middleName = input("Middle name (optional)")
        val lastName = input("Last name / contact last name")
        val companyName = input("Company name (company only)")
        val companyReg = input("Company registration number (company only)")
        val phoneCode = input("Phone country code").apply { setText(R.string.phone_country_code_default) }
        val phoneLocal = input("Phone number (local part)", InputType.TYPE_CLASS_PHONE)
        val email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val idNumber = input("ID number (individual)")
        val address = input("Address")
        val taxPin = input("Tax PIN (optional)")
        val connectionType = input("Connection type: domestic, commercial or industrial").apply { setText(R.string.connection_type_domestic) }
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
                .put("customer_type", customerType.text.toString().trim().lowercase())
                .put("first_name", firstName.text.toString().trim())
                .put("middle_name", middleName.text.toString().trim())
                .put("last_name", lastName.text.toString().trim())
                .put("company_name", companyName.text.toString().trim())
                .put("company_registration_number", companyReg.text.toString().trim())
                .put("phone_country_code", phoneCode.text.toString().trim())
                .put("phone_number_local", phoneLocal.text.toString().trim())
                .put("email", email.text.toString().trim())
                .put("id_number", idNumber.text.toString().trim())
                .put("address", address.text.toString().trim())
                .put("tax_pin", taxPin.text.toString().trim())
                .put("connection_type", connectionType.text.toString().trim().lowercase())
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
                val (identifierBox, identifier) = searchableIdentifierField("Account, meter number or name")
                val reading = input("Current reading", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                val billingMonth = datePickerInput("Billing month", data.optString("default_billing_month"))
                val dueDate = datePickerInput("Due date", data.optString("default_due_date"))
                val submit = actionButton("Create bill from reading")
                val form = screen("Invoicing", "Create a pending bill from a meter reading")
                form.addView(identifierBox)
                listOf(reading, billingMonth, dueDate, submit).forEach(form::addView)
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
                form.addView(buttonRow(
                    "Today" to { childScreen(); loadReports("today") },
                    "This month" to { childScreen(); loadReports("this_month") },
                    "This year" to { childScreen(); loadReports("this_year") }
                ))
                val summary = data.optJSONObject("summary") ?: JSONObject()
                form.addView(card("Billed total", money(summary.optDouble("billed_total"))))
                form.addView(card("Completed payments", money(summary.optDouble("completed_payments_total"))))
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
    }

    private fun show(form: LinearLayout, transient: Boolean = false) {
        closeActivePdf()
        screenEpoch++
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageBackground)
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
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.post {
            ViewCompat.requestApplyInsets(scroll)
        }
        // Status/navigation bar backgrounds and icon appearance both derive from
        // the effective night mode: dark background + light icons at night,
        // light background + dark icons otherwise.
        val lightBars = !isNightModeActive()
        WindowInsetsControllerCompat(window, scroll).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        if (!transient) {
            val previous = displayedScreen
            if (previous != null && previous.view !== scroll) {
                navigationHistory += previous
                if (navigationHistory.size > 30) navigationHistory.removeAt(0)
            }
            displayedScreen = ScreenSnapshot(scroll, backAction)
        }
    }

    private fun screen(header: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = header
        setPadding(dp(18), 0, dp(18), dp(32))
        setBackgroundColor(pageBackground)
        addView(headerBar(header, subtitle), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = -dp(18)
            rightMargin = -dp(18)
            bottomMargin = dp(18)
        })
        backAction?.let { action ->
            addView(backNavigationRow(action))
        }
    }

    private fun headerBar(header: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(16))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = dp(18).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            colors = intArrayOf(navy, navyDark)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        }
        addView(title(header))
        if (subtitle.isNotBlank()) {
            addView(body(subtitle).apply {
                setTextColor(Color.rgb(198, 214, 235))
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun backNavigationRow(action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        isClickable = true
        isFocusable = true
        contentDescription = "Go back"
        setPadding(dp(14), 0, dp(16), 0)
        background = roundedDrawable(cardBackground, border, 14f)
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
        textSize = 21f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        letterSpacing = -0.01f
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textPrimary)
        setPadding(dp(2), dp(20), 0, dp(10))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(muted)
        setLineSpacing(dp(2).toFloat(), 1f)
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
            startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
        } catch (_: Exception) {
            toast(getString(R.string.link_unavailable))
        }
    }

    private fun empty(value: String) = body(value).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, dp(24))
        background = roundedDrawable(cardBackgroundMuted, border, 16f)
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
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = roundedDrawable(cardBackground, border, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            minHeight = dp(58)
        }

    private fun multilineInput(hintText: String) = EditText(this).apply {
        hint = hintText
        minLines = 4
        gravity = Gravity.TOP
        textSize = 16f
        setTextColor(textPrimary)
        setHintTextColor(muted)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedDrawable(cardBackground, border, 14f)
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

    private fun accountOptions(accounts: JSONArray): List<Pair<String, String>> =
        (0 until accounts.length()).mapNotNull { index ->
            val account = accounts.optJSONObject(index) ?: return@mapNotNull null
            val id = account.optInt("id")
            if (id <= 0 || !account.optBoolean("is_active", true)) return@mapNotNull null
            val code = account.optString("code").trim()
            val name = account.optString("name").trim()
            val type = account.optString("account_type")
                .replace('_', ' ')
                .replaceFirstChar(Char::uppercase)
            val title = listOf(code, name).filter(String::isNotBlank).joinToString(" • ")
            (id.toString()) to listOf(title, type).filter(String::isNotBlank).joinToString(" — ")
        }

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

    private fun datePickerInput(hintText: String, initialValue: String): EditText {
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
                    field.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
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
        background = roundedDrawable(tintBlue, tintBlue, 12f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
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
        cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(primary)
        minHeight = dp(54)
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
        cornerRadius = dp(14)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(border)
        backgroundTintList = ColorStateList.valueOf(cardBackground)
        minHeight = dp(52)
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
        cornerRadius = dp(14)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(danger)
        backgroundTintList = ColorStateList.valueOf(tintRed)
        minHeight = dp(52)
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
        background = roundedDrawable(tone.tint, tone.border, 16f)
        elevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        tone.accent?.let { accentColor ->
            addView(View(this@MainActivity).apply {
                setBackgroundColor(accentColor)
                layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        addView(TextView(this@MainActivity).apply {
            val content = "$label\n$value"
            text = SpannableString(content).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.8f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(muted), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 17f
            setTextColor(textPrimary)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = roundedDrawable(tone.tint, tone.border, 16f)
        elevation = dp(1).toFloat()
        minimumHeight = dp(108)
        addView(LinearLayout(this@MainActivity).apply {
            background = roundedDrawable(cardBackground, cardBackground, 10f)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            addView(iconView(iconRes, tone.onTint, 18))
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(9) })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 17f
            setTextColor(tone.onTint)
            setTypeface(typeface, Typeface.BOLD)
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
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedDrawable(cardBackground, border, 16f)
        elevation = dp(1).toFloat()
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
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
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
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = roundedDrawable(cardBackground, border, 14f)
        elevation = dp(1).toFloat()
        setOnClickListener { action() }
        addView(iconView(iconRes, primaryDark, 20).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(8) }
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            maxLines = 2
        })
    }

    private fun addField(parent: LinearLayout, label: String, value: String) {
        parent.addView(card(label, value.ifBlank { "—" }))
    }

    private fun addMenu(parent: LinearLayout, label: String, action: () -> Unit, description: String = "") {
        parent.addView(navRow(label, description, action))
    }

    private fun navRow(title: String, description: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = dp(50)
        background = roundedDrawable(cardBackground, border, 14f)
        setPadding(dp(16), dp(12), dp(14), dp(12))
        elevation = dp(1).toFloat()
        contentDescription = if (description.isNotBlank()) "$title. $description" else title
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(9) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            if (description.isNotBlank()) {
                addView(body(description).apply {
                    textSize = 12.5f
                    setPadding(0, dp(2), 0, 0)
                })
            }
        })
        addView(iconView(R.drawable.ic_chevron_right, muted, 20).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        })
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
            setPadding(dp(16), dp(15), dp(15), dp(15))
            background = roundedDrawable(cardBackground, border, 16f)
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
                background = roundedDrawable(tone.tint, tone.tint, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(iconView(iconRes, tone.onTint, 22))
            }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
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
        displayedScreen = previous
        backAction = previous.backAction
        setContentView(previous.view)
        ViewCompat.requestApplyInsets(previous.view)
        return true
    }

    private fun addUrlButton(parent: LinearLayout, label: String, url: String) {
        if (url.isBlank()) return
        parent.addView(secondaryButton(label).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: Exception) {
                    toast("No app can open this link.")
                }
            }
        })
    }

    private fun addDocumentButton(parent: LinearLayout, label: String, url: String) {
        if (url.isBlank()) return
        parent.addView(secondaryButton(label).apply {
            setOnClickListener { showDocumentViewer(url, label.removePrefix("View ").removePrefix("Open ")) }
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
                            document.contentType.startsWith("text/html") ||
                                document.contentType.startsWith("text/plain") ->
                                showHtmlDocument(url, document, title)
                            document.contentType.startsWith("image/") ->
                                showImageDocument(document.file, title)
                            else -> {
                                document.file.delete()
                                handleError(IllegalStateException("This document format is not supported in the app."))
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

    private fun showHtmlDocument(url: String, document: DownloadedDocument, title: String) {
        val form = screen(title, "")
        val html = document.file.readText(Charsets.UTF_8)
        document.file.delete()
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val target = request.url
                    if (target.scheme == "https") openExternal(target.toString())
                    return true
                }
            }
            loadDataWithBaseURL(
                url,
                html,
                "text/html",
                "UTF-8",
                null
            )
        }
        form.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels - dp(190)).coerceAtLeast(dp(240))
        ))
        addBack(form)
        show(form)
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
                fields.drop(1).joinToString(" • ") { row.optString(it) }.trim()
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
                        .filter(String::isNotBlank).joinToString(" • ")
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
        onClick: ((JSONObject) -> Unit)? = null
    ) {
        parent.addView(sectionTitle(heading))
        if (rows == null || rows.length() == 0) {
            parent.addView(empty("No records found."))
            return
        }
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val title = titleFields.map { row.optString(it) }.firstOrNull(String::isNotBlank)
                ?: (heading.removeSuffix("s") + " #" + (index + 1))
            val excluded = titleFields.toSet()
            val keys = row.keys().asSequence().filter { it !in excluded && it != "id" }.sorted().take(6).toList()
            val subtitle = keys.joinToString("\n") { key ->
                "${key.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }}: ${formatValue(key, row.opt(key))}"
            }
            val view = card(title, subtitle)
            if (onClick != null) {
                view.isClickable = true
                view.isFocusable = true
                view.setOnClickListener { onClick(row) }
            }
            parent.addView(view)
        }
    }

    private fun renderNode(parent: LinearLayout, label: String, value: Any?) {
        when (value) {
            is JSONObject -> {
                if (value.length() == 0) return
                parent.addView(sectionTitle(label))
                value.keys().asSequence().sorted().forEach { key ->
                    val child = value.opt(key)
                    val childLabel = key.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }
                    when (child) {
                        is JSONObject, is JSONArray -> renderNode(parent, childLabel, child)
                        else -> addField(parent, childLabel, formatValue(key, child))
                    }
                }
            }
            is JSONArray -> addRecordList(parent, label, value)
            else -> {}
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

    private fun signOutButton() = secondaryButton("Sign out").apply {
        setOnClickListener {
            api.request("logout.php", "POST") {
                runOnUiThread { clearSession() }
            }
        }
    }

    private fun clearSession() {
        navigationHistory.clear()
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
