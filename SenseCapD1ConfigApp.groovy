definition(
    name: "SenseCap D1 Config",
    namespace: "ienam-smarthome",
    author: "iEnam / ChatGPT",
    description: "Stores SenseCap D1 firmware settings in Hubitat and can push screensaver settings directly to the D1.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    oauth: true
)

preferences {
    page(name: "mainPage", title: "SenseCap D1 Config", install: true, uninstall: true) {
        if (settings?.d1InstanceLabel) {
            app.updateLabel(settings.d1InstanceLabel as String)
        }
        // Auto-suggest the D1's timezone from the hub's own "Time Zone" setting (Hub
        // Details page) the first time this app is configured, so most users never have
        // to look up a POSIX TZ string manually. Only fires while d1Timezone is still
        // blank - once a value exists (auto-filled or hand-entered), it's left alone,
        // so this never overwrites a deliberate choice.
        // getInstallationState() != "COMPLETE" guards the very first render of a
        // brand-new app instance: app.updateSetting() needs a row in Hubitat's
        // app_setting table keyed by installed_app_id, which doesn't exist yet at that
        // point (the app record itself isn't created until the first Done/Save), so
        // calling it earlier throws a NULL-constraint SQL error. The try/catch is a
        // second layer of defense in case that state check itself isn't reliable on
        // every Hubitat platform version - matches the same defensive pattern already
        // used for createAccessToken() below.
        if (!settings?.d1Timezone && app.getInstallationState() == "COMPLETE") {
            try {
                String suggested = suggestedPosixTzFromHub()
                if (suggested) {
                    app.updateSetting("d1Timezone", [type: "text", value: suggested])
                }
            } catch (ignored) {}
        }
        // Same idea for date order: US date format (MM/DD/YYYY) is genuinely a US-only
        // convention, not a general "America/*" one - Canada, Mexico, Brazil, and the
        // rest of the Americas use day/month order same as most of the world, so this
        // checks a curated set of actual US zone IDs (the same ones already in
        // suggestedPosixTzFromHub()'s table) rather than a broad prefix match. Only
        // fires while the setting is still unset (Groovy's == true test below makes
        // "unset" and "explicitly off" indistinguishable, so this uses containsKey
        // instead to only ever set it once, on a genuinely blank value).
        if (!settings?.containsKey("d1DateFormatUS") && app.getInstallationState() == "COMPLETE") {
            try {
                if (isUSTimezoneFromHub()) {
                    app.updateSetting("d1DateFormatUS", [type: "bool", value: true])
                }
            } catch (ignored) {}
        }
        section("App Name") {
            input "d1InstanceLabel", "text", title: "Name for this D1 (shown in your Apps list)", required: false, submitOnChange: true, description: "e.g. 'Living Room D1' or 'Bedroom D1' - helps tell multiple D1 instances apart when running more than one screen. Leave blank to use the default name."
        }
        section("D1 Device") {
            input "d1DeviceIp", "text", title: "D1 local IP address", required: false, description: "Example: 192.168.1.123"
            input "d1DevicePort", "number", title: "D1 local config port", required: false, defaultValue: 8080
            input "d1FullSyncIntervalMinutes", "number", title: "Periodic full sync interval (minutes)", required: false, defaultValue: 15, range: "5..59", description: "Safety-net resync in case a live event is ever missed. Lower = catches drift faster but pushes the full device list to the D1 more often; higher = less traffic but slower to self-correct."
            input "d1Timezone", "text", title: "Timezone (POSIX TZ string, including DST rule)", required: false, submitOnChange: true, description: "Controls the D1's on-screen clock. Must include the DST transition rule, not just a UTC offset, or the clock will be an hour off for half the year. Auto-filled from your hub's Time Zone setting (Hub Details page) when recognized - edit it if it's wrong or your zone wasn't recognized."
            if (!settings?.d1Timezone) {
                String hubZoneId = null
                try { hubZoneId = location?.timeZone?.getID() } catch (ignored) {}
                paragraph "Your hub's timezone (${hubZoneId ?: 'unknown'}) isn't in this app's auto-detect list, so the Timezone field above is blank - the D1 will show UK time until you fill it in. Look up your zone's POSIX TZ string at " +
                          "https://www.veron.nl/wp-content/uploads/2022/12/Posix-Timezone-Strings.pdf (a comprehensive reference list), paste it into the field above, and click \"Send settings to D1 now\". " +
                          "Common examples: US Eastern is 'EST5EDT,M3.2.0,M11.1.0', most of continental Europe is 'CET-1CEST,M3.5.0,M10.5.0/3', Australian Eastern is 'AEST-10AEDT,M10.1.0,M4.1.0/3'."
            }
            input "d1DateFormatUS", "bool", title: "Use US date format (MM/DD/YYYY)", required: false, defaultValue: false, description: "Off (default) shows the screensaver date as day/month/year, e.g. 'Saturday, 22/08/2026'. On shows month/day/year, e.g. 'Saturday, 08/22/2026'. Auto-enabled for US hub timezones on first install - toggle it yourself any time."
            input name: "syncD1Now", type: "button", title: "Send settings to D1 now"
            paragraph "Enter the D1 IP address above. The app will send settings directly to http://D1-IP:8080/d1/config, so you no longer need to paste D1_REMOTE_CONFIG_URL into app_config.h for screensaver control."
        }
        section("Network") {
            input "wifiSsid", "text", title: "Wi-Fi SSID", required: false
            input "wifiPassword", "password", title: "Wi-Fi Password", required: false
        }
        section("Motion display control") {
            input "motionDisplayControlEnabled", "bool", title: "Enable motion-controlled display on/off", required: false, defaultValue: false, submitOnChange: true
            if (settings?.motionDisplayControlEnabled == true) {
                input "displayMotionSensor", "capability.motionSensor", title: "Motion sensor for display wake/sleep", required: true, multiple: false, submitOnChange: true
                input "displayOffAfterInactiveMinutes", "number", title: "Turn display off after motion inactive for X minutes", required: false, defaultValue: 5, range: "1..240"
                input "wakeDisplayOnMotion", "bool", title: "Turn display on when motion becomes active", required: false, defaultValue: true
                input "motionWakeToScreensaver", "bool", title: "Wake to screensaver instead of dashboard", required: false, defaultValue: true
                paragraph "Event driven: subscribes to one motion sensor only. No polling. Inactive schedules a one-shot timer; active cancels it and wakes the display."
            }
        }

        section("Live update push") {
            paragraph "Select the D1 dashboard devices here. This app pushes the full device snapshot to the D1, subscribes to live updates, and handles D1 touch commands for the same devices."
            input "liveUpdateDevices", "capability.*", title: "D1 dashboard devices", required: false, multiple: true, submitOnChange: true
            input "liveEventDebug", "bool", title: "Debug event logs", required: false, defaultValue: false
            input "liveTelemetryDebounceMinutes", "number", title: "Telemetry debounce minutes", required: false, defaultValue: 0, range: "0..60"
            input "livePushPowerMeters", "bool", title: "Bridge power/energy events", required: false, defaultValue: false
        }
        section("Weather") {
            input "weatherDevice", "capability.temperatureMeasurement", title: "Hubitat weather device", required: false, multiple: false
            input "weatherRefreshMinutes", "number", title: "Fallback weather refresh minutes", required: false, defaultValue: 15, range: "1..1440"
            input "weatherLatitude", "text", title: "Fallback latitude", required: false
            input "weatherLongitude", "text", title: "Fallback longitude", required: false
        }
        section("Screensaver") {
            input "screensaverTimeoutMinutes", "number", title: "Screensaver timeout minutes", required: false, defaultValue: 2
            input "dayBrightnessPercent", "number", title: "Day brightness %", required: false, defaultValue: 100, range: "1..100"
            input "nightBrightnessPercent", "number", title: "Night brightness %", required: false, defaultValue: 25, range: "1..100"
            input "dayBrightnessStart", "time", title: "Day brightness starts", required: false, defaultValue: "07:00"
            input "nightBrightnessStart", "time", title: "Night brightness starts", required: false, defaultValue: "22:00"
            paragraph "Brightness is schedule-based. Hubitat pushes the active brightness on save/boot and at the two configured transition times; no brightness polling is used."
            paragraph "Set to 0 to disable. The D1 wakes from screensaver with a screen tap or the side button. Settings are pushed to the D1 when you save/update this app or press a D1 action button."
            input name: "startScreensaverNow", type: "button", title: "Start screensaver now"
        }
        section("Optional pull endpoint") {
            paragraph "Optional only: /config is still available for firmware builds that prefer polling this app. For normal use, enter the D1 local IP above and the app will push settings directly to the D1."
        }
    }
}

mappings {
    path("/config") { action: [GET: "config"] }
    path("/command") { action: [POST: "command"] }
}

def installed() { updateAppLabelFromSettings(); applySuggestedTimezoneIfBlank(); applySuggestedDateFormatIfUnset(); initialize(); pushSettingsToD1(false) }
def updated() { updateAppLabelFromSettings(); unsubscribe(); initialize(); pushSettingsToD1(false) }

private void applySuggestedTimezoneIfBlank() {
    // installed() runs right after the very first Done/Save completes, by which point
    // the app instance genuinely exists (unlike the mainPage-closure attempt, which is
    // gated on getInstallationState() == "COMPLETE" and so skips this on that very
    // first render) - calling it here too means the timezone is auto-filled
    // immediately on first install rather than requiring the user to reopen the page a
    // second time before the suggestion appears.
    if (settings?.d1Timezone) return
    try {
        String suggested = suggestedPosixTzFromHub()
        if (suggested) {
            app.updateSetting("d1Timezone", [type: "text", value: suggested])
        }
    } catch (ignored) {}
}

private void applySuggestedDateFormatIfUnset() {
    // Mirrors applySuggestedTimezoneIfBlank() above, for the same "fill in on first
    // install rather than requiring a second page visit" reason. containsKey (not a
    // truthiness check) is deliberate: an explicit "off" and "never set" both read as
    // falsy otherwise, and only the latter should trigger a suggestion.
    if (settings?.containsKey("d1DateFormatUS")) return
    try {
        if (isUSTimezoneFromHub()) {
            app.updateSetting("d1DateFormatUS", [type: "bool", value: true])
        }
    } catch (ignored) {}
}

private void updateAppLabelFromSettings() {
    // Mirrors the live update already done inline in the mainPage closure above (which
    // only fires while the page is actually open) - this covers the install/save
    // transitions too, so a label typed in before the very first "Done" still sticks.
    if (settings?.d1InstanceLabel) {
        app.updateLabel(settings.d1InstanceLabel as String)
    }
}

private Boolean isUSTimezoneFromHub() {
    // US date order (MM/DD/YYYY) is a genuinely US-only convention - Canada, Mexico,
    // and the rest of the Americas use day/month order like most of the world, so this
    // checks a curated set of actual US zone IDs rather than a broad "America/*"
    // prefix match, which would wrongly catch America/Toronto, America/Mexico_City,
    // America/Sao_Paulo, etc.
    String zoneId = null
    try { zoneId = location?.timeZone?.getID() } catch (ignored) {}
    if (!zoneId) return false
    List<String> usZones = [
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
        "America/Anchorage", "America/Phoenix", "Pacific/Honolulu",
    ]
    return usZones.contains(zoneId)
}

private String suggestedPosixTzFromHub() {
    // Hub Details exposes a Time Zone setting backed by a standard IANA/Olson zone ID
    // (location.timeZone.getID(), e.g. "Europe/London") - but the D1 firmware needs a
    // POSIX TZ string with an explicit DST transition rule baked in (e.g.
    // "GMT0BST,M3.5.0/1,M10.5.0/2"), and there's no reliable way to derive one
    // generically from a Java TimeZone object: DST transition dates/times vary by
    // region, some zones don't observe DST at all, and getting this wrong silently
    // means a clock that's an hour off for half the year - worse than just asking the
    // user to enter it. A curated table of common zones is more reliable than
    // algorithmic derivation, at the cost of not covering every zone Hubitat supports;
    // anything not listed here falls through to manual entry (see the field's
    // description).
    String zoneId = null
    try { zoneId = location?.timeZone?.getID() } catch (ignored) {}
    if (!zoneId) return null

    Map<String, String> known = [
        "Europe/London"      : "GMT0BST,M3.5.0/1,M10.5.0/2",
        "Europe/Berlin"      : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Paris"       : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Madrid"      : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Rome"        : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Amsterdam"   : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Brussels"    : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Vienna"      : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Warsaw"      : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Stockholm"   : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Copenhagen"  : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Zurich"      : "CET-1CEST,M3.5.0,M10.5.0/3",
        "Europe/Lisbon"      : "WET0WEST,M3.5.0/1,M10.5.0/2",
        "Europe/Athens"      : "EET-2EEST,M3.5.0/3,M10.5.0/4",
        "Europe/Helsinki"    : "EET-2EEST,M3.5.0/3,M10.5.0/4",
        "Europe/Bucharest"   : "EET-2EEST,M3.5.0/3,M10.5.0/4",
        "America/New_York"   : "EST5EDT,M3.2.0,M11.1.0",
        "America/Chicago"    : "CST6CDT,M3.2.0,M11.1.0",
        "America/Denver"     : "MST7MDT,M3.2.0,M11.1.0",
        "America/Los_Angeles": "PST8PDT,M3.2.0,M11.1.0",
        "America/Anchorage"  : "AKST9AKDT,M3.2.0,M11.1.0",
        "America/Phoenix"    : "MST7",
        "Pacific/Honolulu"   : "HST10",
        "America/Toronto"    : "EST5EDT,M3.2.0,M11.1.0",
        "America/Vancouver"  : "PST8PDT,M3.2.0,M11.1.0",
        "Australia/Sydney"   : "AEST-10AEDT,M10.1.0,M4.1.0/3",
        "Australia/Melbourne": "AEST-10AEDT,M10.1.0,M4.1.0/3",
        "Australia/Brisbane" : "AEST-10",
        "Australia/Perth"    : "AWST-8",
        "Australia/Adelaide" : "ACST-9:30ACDT,M10.1.0,M4.1.0/3",
        "Pacific/Auckland"   : "NZST-12NZDT,M9.5.0,M4.1.0/3",
        "Asia/Tokyo"         : "JST-9",
        "Asia/Shanghai"      : "CST-8",
        "Asia/Singapore"     : "SGT-8",
        "Asia/Kolkata"       : "IST-5:30",
        "Asia/Dubai"         : "GST-4",
        "UTC"                : "UTC0",
    ]
    return known[zoneId]
}
def initialize() {
    try {
        if (!state.accessToken) createAccessToken()
    } catch (ignored) {}
    state.d1Offline = false
    state.d1OfflineRetryAt = null
    subscribeMotionDisplayControl()
    subscribeLiveUpdateDevices()
    if (state.screensaverCommandId == null) state.screensaverCommandId = 0L
    scheduleD1BrightnessTransitions()

    // resumeD1LiveUpdates() (health probe -> markD1Online -> pushSettingsToD1) and a
    // periodic full resync both existed as callable handlers but were never actually
    // scheduled anywhere, so once state.d1Offline flipped true (a reboot, a brief Wi-Fi
    // drop) nothing ever probed again - the dashboard stayed marked offline and stopped
    // receiving live pushes until the next app save. Wire both up here.
    try { unschedule("resumeD1LiveUpdates") } catch (ignored) {}
    runEvery10Minutes("resumeD1LiveUpdates")

    try { unschedule("d1PeriodicFullSync") } catch (ignored) {}
    // Hubitat's runEvery*Minutes() helpers only support fixed increments (5, 10, 15,
    // 30...), not an arbitrary user-chosen value, so a cron schedule is used instead -
    // this makes the "Periodic full sync interval" setting actually take effect at
    // whatever minute value the user picks. Clamped to 59 (not just defensively - a
    // Quartz cron minute-field step like "0/90" doesn't mean "every 90 minutes", it's
    // meaningless past 59, so values above that would silently misbehave).
    Integer syncMinutes = ((settings?.d1FullSyncIntervalMinutes ?: 15) as Integer)
    syncMinutes = Math.max(5, Math.min(syncMinutes, 59))
    schedule("0 0/${syncMinutes} * * * ?", "d1PeriodicFullSync")
}

def d1PeriodicFullSync() {
    pushSettingsToD1(false)
}

def appButtonHandler(btn) {
    if (btn == "startScreensaverNow") {
        state.screensaverCommandId = now()
        state.screensaverStartRequested = true
        pushSettingsToD1(true, true)
    } else if (btn == "syncD1Now") {
        pushSettingsToD1(false, true)
    }
}

private String val(def v) { v == null ? "" : v.toString() }

private Integer d1BrightnessPercent(Object v, Integer fallback) {
    try {
        Integer p = (v == null ? fallback : v.toString().toInteger())
        if (p < 1) return 1
        if (p > 100) return 100
        return p
    } catch (ignored) { return fallback }
}

private String d1HHmm(Object v, String fallback) {
    if (v == null) return fallback
    def m = (v.toString() =~ /(\d{1,2}):(\d{2})/)
    if (m.find()) {
        Integer h = m.group(1).toInteger(); Integer mi = m.group(2).toInteger()
        if (h >= 0 && h <= 23 && mi >= 0 && mi <= 59) return String.format('%02d:%02d', h, mi)
    }
    return fallback
}

private Integer d1BrightnessMinutes(String hhmm) {
    def m = (hhmm =~ /(\d{1,2}):(\d{2})/)
    if (!m.find()) return 0
    return m.group(1).toInteger() * 60 + m.group(2).toInteger()
}

private Boolean d1IsDayBrightnessNow() {
    String day = d1HHmm(settings?.dayBrightnessStart, '07:00')
    String night = d1HHmm(settings?.nightBrightnessStart, '22:00')
    Integer d = d1BrightnessMinutes(day)
    Integer n = d1BrightnessMinutes(night)
    Calendar cal = Calendar.getInstance(location.timeZone ?: TimeZone.getDefault())
    Integer nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    if (d == n) return true
    if (d < n) return nowMin >= d && nowMin < n
    return nowMin >= d || nowMin < n
}

private Integer currentD1BrightnessPercent() {
    return d1IsDayBrightnessNow() ? d1BrightnessPercent(settings?.dayBrightnessPercent, 100) : d1BrightnessPercent(settings?.nightBrightnessPercent, 25)
}

private Long liveTelemetryDebounceMs() {
    try {
        Integer minutes
        if (settings?.liveTelemetryDebounceMinutes != null) {
            minutes = settings.liveTelemetryDebounceMinutes.toString().toInteger()
        } else if (settings?.liveTelemetryDebounceMs != null) {
            Integer legacyMs = settings.liveTelemetryDebounceMs.toString().toInteger()
            minutes = legacyMs < 60000 ? 0 : Math.round(legacyMs / 60000.0) as Integer
        } else {
            minutes = 0
        }
        if (minutes < 0) minutes = 0
        if (minutes > 60) minutes = 60
        return minutes * 60000L
    } catch (ignored) {
        return 0L
    }
}

private Integer d1WeatherRefreshMs() {
    try {
        Integer minutes
        if (settings?.weatherRefreshMinutes != null) {
            minutes = settings.weatherRefreshMinutes.toString().toInteger()
        } else if (settings?.weatherRefreshMs != null) {
            Integer legacyMs = settings.weatherRefreshMs.toString().toInteger()
            minutes = Math.max(1, Math.round(legacyMs / 60000.0) as Integer)
        } else {
            minutes = 15
        }
        if (minutes < 1) minutes = 15
        if (minutes > 1440) minutes = 1440
        return minutes * 60000
    } catch (ignored) {
        return 900000
    }
}

def d1BrightnessScheduleHandler() {
    pushSettingsToD1(false)
}

private void scheduleD1BrightnessTransitions() {
    try { unschedule('d1BrightnessScheduleHandler') } catch (ignored) {}
    String day = d1HHmm(settings?.dayBrightnessStart, '07:00')
    String night = d1HHmm(settings?.nightBrightnessStart, '22:00')
    [day, night].unique().each { hhmm ->
        Integer h = hhmm.substring(0,2).toInteger()
        Integer m = hhmm.substring(3,5).toInteger()
        schedule("0 ${m} ${h} ? * *", 'd1BrightnessScheduleHandler')
    }
}




private List<String> weatherConditionAttributes() {
    return [
        "condition_text",
        "conditionText", "weatherCondition", "currentCondition",
        "currentConditions", "conditions", "condition", "weather", "sky", "wxPhraseLong",
        "wxPhraseShort", "phrase", "summary"
    ]
}

private List<String> weatherCodeAttributes() {
    return ["weatherCode", "weather_code", "weathercode", "conditionCode", "condition_code", "iconCode", "code"]
}

private String currentDeviceValue(def dev, List<String> attrs) {
    if (!dev) return ""
    for (String attr : attrs) {
        try {
            if (dev.hasAttribute(attr)) {
                def value = dev.currentValue(attr)
                if (value != null && value.toString().trim()) return value.toString()
            }
        } catch (ignored) {}
    }
    return ""
}

private Map currentWeatherMap() {
    def dev = settings.weatherDevice
    if (!dev) return [:]
    return [
        temperature: currentDeviceValue(dev, ["temperature", "temp", "currentTemperature"]),
        condition: currentDeviceValue(dev, weatherConditionAttributes()),
        weatherCode: currentDeviceValue(dev, weatherCodeAttributes()),
        humidity: currentDeviceValue(dev, ["humidity"])
    ]
}

private List getD1DashboardDevices() {
    def selected = []
    try {
        if (settings?.liveUpdateDevices) selected.addAll(settings.liveUpdateDevices.findAll { it != null })
    } catch (ignored) {}

    def seen = [:]
    selected.findAll { dev ->
        if (!dev?.id) return false
        String id = dev.id.toString()
        if (seen[id]) return false
        seen[id] = true
        return true
    }
}

private List uniqueDevices(List selected) {
    def seen = [:]
    selected.findAll { dev ->
        if (!dev?.id) return false
        String id = dev.id.toString()
        if (seen[id]) return false
        seen[id] = true
        return true
    }
}

private List getD1ServiceDevices() {
    def selected = []
    try {
        selected.addAll(getD1DashboardDevices())
        if (settings?.weatherDevice) selected << settings.weatherDevice
    } catch (ignored) {}
    return uniqueDevices(selected)
}

private List getLiveUpdateDevices() {
    return getD1ServiceDevices()
}

private List<String> d1SnapshotAttributesForDevice(dev) {
    // Previously this list only covered switch/level/power/temperature/humidity/contact/
    // motion/illuminance/thermostat*/battery - any device reporting presence, occupancy,
    // lock, smoke, water, CO, tamper, valve or window-shade state was never included in
    // the snapshot at all, so those tiles never got a value from Hubitat in the first
    // place (not just "stale" - genuinely never populated).
    def attrs = [
        "switch", "level", "power", "temperature", "humidity", "contact", "motion",
        "illuminance", "battery", "presence", "occupancy", "lock",
        "heatingSetpoint", "thermostatSetpoint", "thermostatMode", "thermostatOperatingState",
        "smoke", "water", "carbonMonoxide", "tamper", "valve", "windowShade"
    ]
    if (settings?.weatherDevice && dev?.id?.toString() == settings.weatherDevice.id?.toString()) {
        attrs += weatherConditionAttributes()
        attrs += weatherCodeAttributes()
    }
    if (settings?.livePushPowerMeters == true) {
        attrs += ["energy"]
    }
    return attrs.unique()
}

private List<String> d1LiveAttributesForDevice(dev) {
    // Same gap as d1SnapshotAttributesForDevice, but worse for live updates specifically:
    // temperature/humidity were ONLY subscribed for whichever single device was configured
    // as the weatherDevice, so any regular temperature/humidity sensor on the dashboard
    // never fired a live event at all - it would only update on the next full sync. This
    // was the primary cause of "stale readings" reported for non-weather sensor tiles.
    def attrs = [
        "switch", "level", "power", "contact", "motion", "presence", "occupancy",
        "illuminance", "battery", "lock", "temperature", "humidity",
        "heatingSetpoint", "thermostatSetpoint", "thermostatMode", "thermostatOperatingState",
        "smoke", "water", "carbonMonoxide", "tamper", "valve", "windowShade"
    ]
    if (settings?.weatherDevice && dev?.id?.toString() == settings.weatherDevice.id?.toString()) {
        attrs += weatherConditionAttributes()
        attrs += weatherCodeAttributes()
    }
    if (settings?.livePushPowerMeters == true) {
        attrs += ["energy"]
    }
    return attrs.unique()
}

private List d1DeviceSnapshotList() {
    getD1DashboardDevices().collect { dev ->
        def attrs = []
        d1SnapshotAttributesForDevice(dev).each { attr ->
            try {
                if (dev.hasAttribute(attr)) {
                    def value = dev.currentValue(attr)
                    if (value != null && value.toString().trim()) {
                        attrs << [name: attr, currentValue: value.toString(), value: value.toString()]
                    }
                }
            } catch (ignored) {}
        }
        [
            id: dev.id?.toString(),
            deviceId: dev.id?.toString(),
            label: dev.displayName?.toString(),
            displayName: dev.displayName?.toString(),
            room: hubitatRoomNameForDevice(dev),
            attributes: attrs
        ]
    }
}

private String d1CommandUrl() {
    // No custom throw here - a thrown exception from createAccessToken() failing was
    // very likely what tripped a "Cannot get property 'body' on null object" error on
    // Save, probably from Hubitat's own internal error handling choking on an
    // exception it didn't expect from this context, not anything in this code
    // directly. Simple flag checks only, nothing ever thrown from this function.
    try {
        if (!state.accessToken) createAccessToken()
    } catch (ignored) {}
    if (!state.accessToken) {
        // createAccessToken() fails if OAuth isn't enabled for this app in Hubitat's
        // Apps Code editor (a separate one-time toggle, not part of this app's own
        // settings page - easy to miss). Previously this was silently swallowed with
        // no logging anywhere: the D1 would just get an empty command URL forever,
        // taps would visually register on screen but never actually reach Hubitat,
        // and nothing in Hubitat's own logs would explain why. Log it loudly instead.
        log.warn "SenseCap D1: no access token available. Touch commands from the D1 will not work until you enable OAuth for this app: Apps Code -> SenseCap D1 Config -> gear icon -> OAuth -> Enable OAuth in Apps -> Update, then re-save this app's settings."
        return ""
    }
    return "${getFullLocalApiServerUrl()}/command?access_token=${state.accessToken}"
}

private String d1ConfigUrl() {
    try {
        if (!state.accessToken) createAccessToken()
    } catch (ignored) {}
    if (!state.accessToken) {
        log.warn "SenseCap D1: no access token available. Enable OAuth for this app: Apps Code -> SenseCap D1 Config -> gear icon -> OAuth -> Enable OAuth in Apps -> Update, then re-save this app's settings."
        return ""
    }
    return "${getFullLocalApiServerUrl()}/config?access_token=${state.accessToken}"
}

private void subscribeLiveUpdateDevices() {
    try {
        unsubscribe(liveDeviceEventHandler)
    } catch (ignored) {}

    def liveDevices = getLiveUpdateDevices()
    if (!liveDevices) {
        log.warn "D1LIVE: No D1 dashboard devices selected."
        return
    }

    Integer subCount = 0
    liveDevices.each { dev ->
        d1LiveAttributesForDevice(dev).each { attr ->
            try {
                if (dev.hasAttribute(attr)) {
                    subscribe(dev, attr, liveDeviceEventHandler)
                    subCount++
                }
            } catch (ignored) {}
        }
    }
    log.info "D1LIVE: subscribed ${subCount} live attribute streams across ${liveDevices.size()} device(s) via Config app bridge."
}

private Boolean d1LiveDeviceAllowed(String deviceId) {
    if (!deviceId) return false
    try {
        return getLiveUpdateDevices().any { it?.id?.toString() == deviceId.toString() }
    } catch (ignored) {
        return false
    }
}



private Boolean d1LivePushAllowed() {
    Long retryAt = (state.d1OfflineRetryAt ?: 0L) as Long
    return now() >= retryAt
}

private void markD1Offline(String reason = "unknown") {
    Integer fails = ((state.d1ConsecutiveFailures ?: 0) as Integer) + 1
    state.d1ConsecutiveFailures = fails

    // A single failed push is NOT backed off - one HTTP 408 under normal jitter shouldn't
    // suppress a live update. From the second consecutive failure on, back off starting at
    // 30s and doubling with each further consecutive failure, capped at 5 minutes (matching
    // markD1ConfigPushFailed()'s cooldown ceiling below). Any successful push - a live
    // event, a display command, or the periodic resumeD1LiveUpdates() health probe - calls
    // markD1Online() and clears this immediately, so a D1 that comes back quickly resumes
    // getting live updates on the very next Hubitat event rather than waiting out a fixed
    // window.
    if (fails >= 2) {
        state.d1Offline = true
        Integer backoffStep = Math.min(fails - 1, 5) // caps doubling at 2^5 = 32x the base
        Long backoffMs = Math.min(30_000L * (1L << backoffStep), 5 * 60 * 1000L)
        state.d1OfflineRetryAt = now() + backoffMs
    }

    if (fails >= 3 && ((state.d1LastLiveWarnAt ?: 0L) as Long) + (5 * 60 * 1000L) < now()) {
        state.d1LastLiveWarnAt = now()
        log.warn "SenseCap D1 live push failed (${reason}). Live updates are suspended; will retry automatically with backoff."
    }
}

private void markD1Online() {
    if (state?.d1Offline == true) {
        log.info "SenseCap D1 is online again. Live updates resumed."
    }
    state.d1Offline = false
    state.d1ConsecutiveFailures = 0
    state.d1OfflineRetryAt = null
    state.d1LastLiveWarnAt = null
}

def resumeD1LiveUpdates() {
    if (!settings?.d1DeviceIp && !settings?.d1LocalIp && !settings?.d1Ip) {
        return
    }

    String rawHost = (settings?.d1DeviceIp ?: settings?.d1LocalIp ?: settings?.d1Ip).toString()
    String host = rawHost.trim().replace("http://", "").replace("https://", "").replaceAll('/.*$', '')
    Integer port = (settings?.d1DevicePort ?: settings?.d1LocalPort ?: settings?.d1ConfigPort ?: 8080) as Integer

    def params = [
        uri: "http://${host}:${port}/d1/config",
        contentType: "application/json",
        requestContentType: "application/json",
        body: [probe: true],
        timeout: 2
    ]

    try {
        asynchttpPost("d1HealthProbeCallback", params)
    } catch (Exception e) {
        markD1Offline(e.message)
    }
}

def d1HealthProbeCallback(resp, data) {
    if (resp?.hasError()) {
        markD1Offline("HTTP ${resp.status}")
    } else {
        markD1Online()
        markD1ConfigPushOk()
        try {
            pushSettingsToD1(false)
        } catch (ignored) {}
    }
}

def liveDeviceEventHandler(evt) {
    if (!evt || !evt.deviceId) return
    if (!d1LiveDeviceAllowed(evt.deviceId.toString())) {
        if (settings?.liveEventDebug == true) {
            log.info "D1LIVE: ignoring unselected device event id=${evt.deviceId} ${evt.name}=${evt.value}"
        }
        return
    }

    if (!(evt.name in ["switch", "motion", "contact", "level", "thermostatMode", "thermostatOperatingState", "heatingSetpoint", "thermostatSetpoint"])) {
        Long debounceMs = liveTelemetryDebounceMs()
        String key = "${evt.deviceId}:${evt.name}"
        Long nowMs = now()
        if (state.liveLastSentAt == null) state.liveLastSentAt = [:]
        Long lastMs = (state.liveLastSentAt[key] ?: 0L) as Long
        if (debounceMs > 0 && (nowMs - lastMs) < debounceMs) return
        state.liveLastSentAt[key] = nowMs
    }

    if (settings?.liveEventDebug == true) {
        log.info "D1LIVE: Hubitat event ${evt.device?.displayName} id=${evt.deviceId} ${evt.name}=${evt.value}"
    }
    pushLiveEventToD1(evt)
}

private void pushLiveEventToD1(evt) {
    if (!d1LivePushAllowed()) return
    if (!settings?.d1DeviceIp) {
        log.warn "D1LIVE: D1 local IP address is not set; cannot push live event."
        return
    }

    String host = settings.d1DeviceIp.toString().trim().replace("http://", "").replace("https://", "").replaceAll('/.*$', '')
    Integer port = (settings?.d1DevicePort ?: 8080) as Integer
    def payload = [
        id: evt.deviceId?.toString(),
        deviceId: evt.deviceId?.toString(),
        label: evt.device?.displayName?.toString(),
        displayName: evt.device?.displayName?.toString(),
        attr: evt.name?.toString(),
        name: evt.name?.toString(),
        value: evt.value?.toString(),
        currentValue: evt.value?.toString(),
        room: hubitatRoomNameForDevice(evt.device)
    ]
    if (settings?.weatherDevice && evt.deviceId?.toString() == settings.weatherDevice.id?.toString()) {
        payload.weather = currentWeatherMap()
    }
    def params = [
        uri: "http://${host}:${port}/d1/event",
        contentType: "application/json",
        requestContentType: "application/json",
        body: payload,
        timeout: 5
    ]

    try {
        if (settings?.liveEventDebug == true) {
            log.info "D1LIVE: POST http://${host}:${port}/d1/event id=${payload.id} attr=${payload.attr} value=${payload.value}"
        }
        asynchttpPost("liveEventCallback", params, [id: payload.id, attr: payload.attr, value: payload.value])
    } catch (Exception e) {
        markD1Offline(e.message)
    }
}

def liveEventCallback(resp, data) {
    if (resp?.hasError()) {
        markD1Offline("HTTP ${resp.status}")
    } else {
        markD1Online()
        if (settings?.liveEventDebug == true) {
            log.info "D1LIVE: D1 accepted live event ${data?.id}/${data?.attr}=${data?.value}: HTTP ${resp.status}"
        }
    }
}

private Map configMap(Boolean startNow = false) {
    Integer mins = (settings.screensaverTimeoutMinutes == null ? 2 : settings.screensaverTimeoutMinutes) as Integer
    String commandUrl = d1CommandUrl()
    String configUrl = d1ConfigUrl()
    return [
        wifi: [ssid: val(settings.wifiSsid), password: val(settings.wifiPassword)],
        hubitat: [commandUrl: commandUrl, configUrl: configUrl],
        devices: d1DeviceSnapshotList(),
        timezone: (settings.d1Timezone ?: "GMT0BST,M3.5.0/1,M10.5.0/2"),
        dateFormatUS: (settings.d1DateFormatUS == true),
        weather: [
            deviceId: settings.weatherDevice ? settings.weatherDevice.id.toString() : "",
            deviceLabel: settings.weatherDevice ? settings.weatherDevice.displayName : "",
            latitude: val(settings.weatherLatitude), longitude: val(settings.weatherLongitude),
            refreshMs: d1WeatherRefreshMs(),
            current: currentWeatherMap()
        ],
        screensaver: [
            timeoutMinutes: mins,
            timeoutMs: mins * 60000,
            startNow: startNow,
            commandId: (state.screensaverCommandId ?: 0L),
            tapToWake: true
        ],
        brightness: [
            percent: currentD1BrightnessPercent(),
            dayPercent: d1BrightnessPercent(settings?.dayBrightnessPercent, 100),
            nightPercent: d1BrightnessPercent(settings?.nightBrightnessPercent, 25),
            dayStart: d1HHmm(settings?.dayBrightnessStart, '07:00'),
            nightStart: d1HHmm(settings?.nightBrightnessStart, '22:00')
        ]
    ]
}



private Boolean d1ConfigPushAllowed() {
    Long retryAt = (state.d1ConfigRetryAt ?: 0L) as Long
    return now() >= retryAt
}

private void markD1ConfigPushFailed(String reason = "unknown") {
    Integer fails = ((state.d1ConfigConsecutiveFailures ?: 0) as Integer) + 1
    state.d1ConfigConsecutiveFailures = fails
    state.d1ConfigRetryAt = now() + (5 * 60 * 1000L)

    if (fails == 1 || (state.d1LastConfigWarnAt == null) || (now() - ((state.d1LastConfigWarnAt ?: 0L) as Long)) > (5 * 60 * 1000L)) {
        state.d1LastConfigWarnAt = now()
        log.warn "SenseCap D1 config push failed (${reason}). Config pushes paused for 5 minutes; live updates are NOT suspended."
    }
}

private void markD1ConfigPushOk() {
    state.d1ConfigConsecutiveFailures = 0
    state.d1ConfigRetryAt = null
    state.d1LastConfigWarnAt = null
}

private void pushSettingsToD1(Boolean startNow = false, Boolean force = false) {
    if (!force && !d1ConfigPushAllowed()) {
        if (settings?.liveEventDebug == true) log.info "SenseCap D1 config push skipped during cooldown; live updates remain enabled."
        return
    }

    if (!settings.d1DeviceIp) {
        log.warn "D1 local IP address is not set; cannot push settings to D1."
        return
    }

    String host = settings.d1DeviceIp.toString().trim().replace("http://", "").replace("https://", "").replaceAll('/.*$', '')
    Integer primaryPort = (settings.d1DevicePort ?: 8080) as Integer
    List<Integer> portsToTry = []
    [primaryPort, 8080, 80].each { p -> if (p && !portsToTry.contains(p as Integer)) portsToTry << (p as Integer) }

    Exception lastError = null
    for (Integer port : portsToTry) {
        String url = "http://${host}:${port}/d1/config"
        try {
            httpPost([uri: url, contentType: "application/json", requestContentType: "application/json", body: configMap(startNow), timeout: 5]) { resp ->
                markD1ConfigPushOk()
                markD1Online()
                log.info "SenseCap D1 accepted config push at ${url}: HTTP ${resp.status}"
            }
            if (startNow) state.screensaverStartRequested = false
            return
        } catch (Exception e) {
            lastError = e
            if (settings?.liveEventDebug == true) log.info "Could not push settings to SenseCap D1 at ${url}: ${e.message}"
        }
    }
    markD1ConfigPushFailed(lastError?.message ?: "config push failed")
}

def config() {
    render(contentType: "application/json", data: configMap((state.screensaverStartRequested ?: false) == true))
}

private Object normalizeDeviceCommandArgument(dev, String cmd, String arg) {
    if (!arg) return arg
    if (cmd == "setLevel") {
        try {
            BigDecimal value = new BigDecimal(arg)
            if (value < 0) value = 0
            if (value > 100) value = 100
            value = value.setScale(0, java.math.RoundingMode.HALF_UP)
            return value.intValue()
        } catch (ignored) {
            return arg
        }
    }
    if (["setHeatingSetpoint", "setCoolingSetpoint", "setThermostatSetpoint"].contains(cmd)) {
        try {
            BigDecimal value = new BigDecimal(arg)
            BigDecimal minValue = new BigDecimal((dev?.currentValue("minHeatingSetpoint") ?: dev?.currentValue("minThermostatSetpoint") ?: 5).toString())
            BigDecimal maxValue = new BigDecimal((dev?.currentValue("maxHeatingSetpoint") ?: dev?.currentValue("maxThermostatSetpoint") ?: 35).toString())

            if (value > (maxValue + 5) && (value / 10) >= minValue && (value / 10) <= maxValue) {
                value = value / 10
            }
            if (value < minValue) value = minValue
            if (value > maxValue) value = maxValue

            value = value.setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
            if (value.scale() <= 0) return value.intValue()
            return value
        } catch (ignored) {
            if (arg ==~ /-?\d+\.0+/) return arg.substring(0, arg.indexOf('.'))
        }
    }
    return arg
}

def command() {
    Map body = [:]
    try {
        body = request?.JSON ?: [:]
    } catch (ignored) {}

    String id = (body.id ?: body.deviceId ?: params.id ?: params.deviceId ?: "").toString()
    String cmd = (body.command ?: params.command ?: "").toString()
    String rawArg = (body.argument ?: body.value ?: params.argument ?: params.value ?: "").toString()
    def dev = getD1DashboardDevices().find { it?.id?.toString() == id }

    if (!dev || !cmd) {
        render(status: 404, contentType: "application/json", data: [ok: false, error: "device or command not allowed"])
        return
    }

    try {
        def arg = normalizeDeviceCommandArgument(dev, cmd, rawArg)
        if (arg) {
            dev."${cmd}"(arg)
        } else {
            dev."${cmd}"()
        }
        render(contentType: "application/json", data: [ok: true])
    } catch (Exception e) {
        render(status: 500, contentType: "application/json", data: [ok: false, error: e.message])
    }
}


private String cleanRoomName(Object value) {
    String room = value == null ? "" : value.toString().trim()
    if (!room || room.equalsIgnoreCase("null") || room.equalsIgnoreCase("none") || room.equalsIgnoreCase("unassigned")) return null
    return room
}

private String hubitatRoomNameForDevice(dev) {
    // Use Hubitat's actual Device Info > Room value.  Do not infer rooms from labels like
    // "Kitchen Linptech", because labels can disagree with the Hubitat room assignment.
    if (!dev) return null

    def room = null
    try { room = cleanRoomName(dev.roomName) } catch (ignored) {}
    if (room) return room

    try { room = cleanRoomName(dev.getRoomName()) } catch (ignored) {}
    if (room) return room

    try { room = cleanRoomName(dev.room?.name) } catch (ignored) {}
    if (room) return room

    try { room = cleanRoomName(dev.getRoom()?.name) } catch (ignored) {}
    if (room) return room

    try {
        def props = dev.properties
        room = cleanRoomName(props?.roomName ?: props?.room ?: props?.roomId)
    } catch (ignored) {}
    if (room) return room

    return null
}

def liveRoomNameForDevice(String devId) {
    if (!devId) return null
    try {
        def dev = getD1DashboardDevices().find { it?.id?.toString() == devId }
        def room = hubitatRoomNameForDevice(dev)
        if (room) return room
    } catch (ignored) {}
    try {
        if (state?.deviceRoomMap && state.deviceRoomMap[devId]) return cleanRoomName(state.deviceRoomMap[devId])
    } catch (ignored) {}
    return null
}


private void subscribeMotionDisplayControl() {
    try {
        unsubscribe(displayMotionHandler)
    } catch (ignored) {}

    if (settings?.motionDisplayControlEnabled == true && settings?.displayMotionSensor) {
        subscribe(settings.displayMotionSensor, "motion", displayMotionHandler)
        log.info "SenseCap D1 motion display control enabled using ${settings.displayMotionSensor.displayName}; off after ${settings?.displayOffAfterInactiveMinutes ?: 5} minute(s) inactive."
    } else {
        try { unschedule("turnD1DisplayOffAfterMotionInactive") } catch (ignored) {}
    }
}

def displayMotionHandler(evt) {
    if (!evt) return

    if (evt.value == "active") {
        try { unschedule("turnD1DisplayOffAfterMotionInactive") } catch (ignored) {}
        state.d1MotionDisplayLastActive = now()

        if (settings?.wakeDisplayOnMotion != false) {
            if (settings?.motionWakeToScreensaver != false) {
                sendD1DisplayCommand("screensaver")
            } else {
                sendD1DisplayCommand("wake")
            }
        }
        return
    }

    if (evt.value == "inactive") {
        Integer mins = ((settings?.displayOffAfterInactiveMinutes ?: 5) as Integer)
        if (mins < 1) mins = 1
        if (mins > 240) mins = 240
        runIn(mins * 60, "turnD1DisplayOffAfterMotionInactive", [overwrite: true])
    }
}

def turnD1DisplayOffAfterMotionInactive() {
    if (settings?.motionDisplayControlEnabled != true) return
    if (!settings?.displayMotionSensor) return

    String motionNow = settings.displayMotionSensor.currentValue("motion")?.toString()
    if (motionNow == "active") {
        return
    }

    sendD1DisplayCommand("display_off")
}

private void sendD1DisplayCommand(String command) {
    if (!d1LivePushAllowed()) {
        return
    }

    String rawHost = (settings?.d1DeviceIp ?: settings?.d1LocalIp ?: settings?.d1Ip ?: settings?.d1LocalAddress ?: "").toString()
    if (!rawHost) return

    String host = rawHost.trim().replace("http://", "").replace("https://", "").replaceAll('/.*$', '')
    Integer port = (settings?.d1DevicePort ?: settings?.d1LocalPort ?: settings?.d1ConfigPort ?: 8080) as Integer

    def payload = [
        command: command,
        source: "motion_display_control"
    ]

    def params = [
        uri: "http://${host}:${port}/d1/event",
        contentType: "application/json",
        requestContentType: "application/json",
        body: payload,
        timeout: 2
    ]

    try {
        asynchttpPost("d1DisplayCommandCallback", params, [command: command])
    } catch (Exception e) {
        try {
            markD1Offline(e.message)
        } catch (ignored) {}
    }
}

def d1DisplayCommandCallback(resp, data) {
    if (resp?.hasError()) {
        try {
            markD1Offline("display command ${data?.command} HTTP ${resp.status}")
        } catch (ignored) {}
    } else {
        try {
            markD1Online()
        } catch (ignored) {}
    }
}
