package com.unigismap.indoormap.lite

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.unigismap.indoormap.core.BuildingInfo
import com.unigismap.indoormap.core.CampusColors
import com.unigismap.indoormap.core.CampusCore
import com.unigismap.indoormap.core.FloorRef

/**
 * Lite build of the UniGISMap app: the same app (same C++ core, shared Kotlin layer in shared/,
 * same data and screens) with the Compose UI re-done in plain framework Views styled after
 * Material 3, so it builds without Google Maven (AndroidX / Compose / AGP).
 * Keep it in step with app/.../ui/ (CampusScreen.kt, MapScreen.kt) when the app's screens change.
 *
 * Screens: the campus map first; a building with floor plans opens its floors on top of it.
 */
class MainActivity : Activity() {

    private lateinit var campus: CampusCore
    private lateinit var p: Palette
    private lateinit var root: FrameLayout

    // ---- Campus screen ----
    private lateinit var campusPlan: CampusPlan
    private lateinit var campusScreen: View
    private lateinit var campusMap: CampusMapView
    private lateinit var buildingCard: LinearLayout
    private lateinit var buildingTitle: TextView
    private lateinit var buildingSubtitle: TextView

    // ---- Floor screen (built when a building is opened) ----
    private var floorScreen: View? = null
    private var building: BuildingInfo? = null
    private lateinit var plan: FloorPlan
    private lateinit var map: FloorMapView
    private lateinit var card: LinearLayout
    private lateinit var cardTitle: TextView
    private lateinit var cardSubtitle: TextView
    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var resultList: LinearLayout
    private var sheetOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        campus = CampusCore.acquire(this)
        campusPlan = CampusPlan.of(campus)
        p = Palette.from(this)
        setupWindow()
        campusScreen = buildCampusScreen()
        root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(p.surface)
            addView(campusScreen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = p.surfaceContainer
        window.navigationBarColor = p.surface
        if (!p.isDark) {
            // SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 26).
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or 0x10
        }
    }

    // ---- Campus screen ----

    private fun buildCampusScreen(): View {
        campusMap = CampusMapView(this, campusPlan).apply {
            palette = p
            onBuildingClick = { selectBuilding(it) }
        }
        val content = FrameLayout(this).apply {
            addView(campusMap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(buildLegend(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
            addView(text(campusPlan.attribution, 11f, p.onSurfaceVariant),
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(12), 0, dp(88), dp(4))
                })
            addView(buildBuildingCard(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(16), dp(16), dp(88), dp(24))
            })
            addView(buildFabs(campusMap, "Показать весь кампус"),
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(dp(16), dp(16), dp(16), dp(16))
                })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildTopBar("Карта кампуса ${campusPlan.name}", onBack = null, onSearch = null),
                LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun buildLegend(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(p.surfaceContainer, 12f)
        elevation = dp(2).toFloat()
        listOf(
            CampusColors.ACADEMIC to "Учебные корпуса",
            CampusColors.DORM to "Общежития",
            CampusColors.FACILITY to "Объекты кампуса",
            CampusColors.METRO to "Метро «Студенческая»",
        ).forEachIndexed { i, (color, label) ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setGravity(Gravity.CENTER_VERTICAL)
                addView(View(context).apply { background = rounded(color, 2f) },
                    LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) })
                addView(text(label, 12f, p.onSurface, medium = true))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) topMargin = dp(4) })
        }
    }

    private fun buildBuildingCard(): View {
        buildingTitle = text("", 16f, p.onSurface, medium = true)
        buildingSubtitle = text("", 14f, p.onSurfaceVariant)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(buildingTitle)
                addView(buildingSubtitle)
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { topMargin = dp(12) })
            addView(iconButton(R.drawable.ic_close, p.onSurfaceVariant, "Закрыть") { selectBuilding(null) },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("Планы этажей", R.drawable.ic_layers) { campusMap.selected?.let { openBuilding(it.info, 0) } },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)).apply { marginEnd = dp(8) })
            addView(chip("Показать", R.drawable.ic_center_focus) { campusMap.selected?.let { campusMap.animateFocus(it.bounds) } },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)))
        }
        buildingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(4), dp(12))
            background = rounded(p.surfaceContainerLow, 12f)
            elevation = dp(2).toFloat()
            addView(header)
            addView(chips, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            visibility = View.INVISIBLE
            isClickable = true
        }
        return buildingCard
    }

    private fun selectBuilding(b: CampusBuilding?) {
        campusMap.selected = b
        if (b != null) {
            buildingTitle.text = b.info.name
            buildingSubtitle.text = buildString {
                append(b.info.kind.title)
                if (b.info.levels > 0) append(" · этажей: ${b.info.levels}")
                append(" · планы: ${b.info.floorLevels.joinToString()} эт.")
            }
        }
        showCard(buildingCard, b != null)
    }

    // ---- Floor screen ----

    private fun openBuilding(info: BuildingInfo, floorIndex: Int) {
        building = info
        floorScreen?.let(root::removeView)
        sheetOpen = false
        plan = FloorPlan.of(campus, FloorRef(info.index, floorIndex.coerceIn(info.floors.indices)))
        floorScreen = buildFloorScreen(info, floorIndex).also {
            root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        campusScreen.visibility = View.GONE
    }

    private fun closeBuilding() {
        floorScreen?.let(root::removeView)
        floorScreen = null
        building = null
        sheetOpen = false
        campusScreen.visibility = View.VISIBLE
    }

    private fun buildFloorScreen(info: BuildingInfo, floorIndex: Int): View {
        map = FloorMapView(this, plan).apply {
            palette = p
            onRoomClick = { selectRoom(it) }
        }
        val content = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (info.floors.size > 1) {
                addView(buildFloorChips(info, floorIndex), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(12), dp(12), dp(12), dp(12))
                })
            }
            addView(buildCard(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(16), dp(16), dp(88), dp(16))
            })
            addView(buildFabs(map, "Показать весь этаж"), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildTopBar(plan.name, onBack = ::closeBuilding, onSearch = ::openSheet), LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        scrim = View(this).apply {
            setBackgroundColor(0x52000000)
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeSheet() }
        }
        return FrameLayout(this).apply {
            setBackgroundColor(p.surface)
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(buildSheet(), FrameLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7f).toInt(), Gravity.BOTTOM))
        }
    }

    /** Floor switcher: one chip per floor with a plan, the shown one filled. */
    private fun buildFloorChips(info: BuildingInfo, current: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        info.floorLevels.forEachIndexed { i, level ->
            val selected = i == current
            val chip = text("$level этаж", 14f, if (selected) p.onSecondaryContainer else p.onSurface, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple(
                    rounded(if (selected) p.secondaryContainer else p.surfaceContainer, 8f,
                        stroke = if (selected) null else p.outlineVariant),
                    p.onSurface,
                )
                setOnClickListener { if (!selected) openBuilding(info, i) }
            }
            addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    // ---- Top app bar ----

    private fun buildTopBar(subtitle: String, onBack: (() -> Unit)?, onSearch: (() -> Unit)?): View = FrameLayout(this).apply {
        setBackgroundColor(p.surfaceContainer)
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setGravity(Gravity.CENTER_HORIZONTAL)
            addView(text("UniGISMap", 22f, p.onSurface))
            addView(text(subtitle, 12f, p.onSurfaceVariant, medium = true))
        }
        addView(titles, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        if (onBack != null) {
            addView(
                iconButton(R.drawable.ic_arrow_back, p.onSurface, "К карте кампуса") { onBack() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(4) },
            )
        }
        if (onSearch != null) {
            addView(
                iconButton(R.drawable.ic_search, p.onSurfaceVariant, "Поиск помещения") { onSearch() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(4) },
            )
        }
    }

    // ---- FABs ----

    private fun buildFabs(view: MapView, fitDescription: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setGravity(Gravity.CENTER_HORIZONTAL)
        val compass = fab(R.drawable.ic_navigation, "Сбросить поворот", small = true) { view.animateResetRotation() }
        // Needle points to the map's "north" (top edge of a plan, true north on the campus).
        view.onCameraChanged = { compass.rotation = view.rotationDeg }
        compass.rotation = view.rotationDeg
        val items = listOf(
            compass,
            fab(R.drawable.ic_add, "Приблизить", small = true) { view.animateZoom(1.5f) },
            fab(R.drawable.ic_remove, "Отдалить", small = true) { view.animateZoom(1 / 1.5f) },
            fab(R.drawable.ic_center_focus, fitDescription, small = false) { view.animateFit() },
        )
        items.forEachIndexed { i, v ->
            val size = if (i == items.lastIndex) dp(56) else dp(40)
            addView(v, LinearLayout.LayoutParams(size, size).apply { if (i > 0) topMargin = dp(12) })
        }
    }

    private fun fab(icon: Int, desc: String, small: Boolean, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(p.onPrimaryContainer)
        contentDescription = desc
        background = ripple(rounded(p.primaryContainer, if (small) 12f else 16f), p.onPrimaryContainer)
        elevation = dp(if (small) 3 else 6).toFloat()
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
    }

    // ---- Room card ----

    private fun buildCard(): View {
        cardTitle = text("", 16f, p.onSurface, medium = true)
        cardSubtitle = text("", 14f, p.onSurfaceVariant)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(cardTitle)
                addView(cardSubtitle)
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { topMargin = dp(12) })
            addView(iconButton(R.drawable.ic_close, p.onSurfaceVariant, "Закрыть") { selectRoom(null) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val focus = chip("Показать на карте", R.drawable.ic_center_focus) { map.selectedRoom?.let { map.animateFocus(it.bounds) } }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(4), dp(12))
            background = rounded(p.surfaceContainerLow, 12f)
            elevation = dp(2).toFloat()
            addView(header)
            addView(focus, LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)).apply { topMargin = dp(8) })
            visibility = View.INVISIBLE
            isClickable = true
        }
        return card
    }

    private fun selectRoom(room: Room?) {
        map.selectedRoom = room
        if (room != null) {
            cardTitle.text = room.name
            cardSubtitle.text = subtitle(room)
        }
        showCard(card, room != null)
    }

    private fun showCard(view: View, show: Boolean) {
        if (show) {
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                view.translationY = dp(200).toFloat()
                view.animate().translationY(0f).setDuration(250).start()
            }
        } else if (view.visibility == View.VISIBLE) {
            view.animate().translationY(dp(200).toFloat()).setDuration(200)
                .withEndAction { view.visibility = View.INVISIBLE }.start()
        }
    }

    // ---- Search bottom sheet ----

    private fun buildSheet(): View {
        searchField = EditText(this).apply {
            hint = "Название или номер"
            setSingleLine()
            setTextColor(p.onSurface)
            setHintTextColor(p.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val icon = getDrawable(R.drawable.ic_search)!!.mutate()
            icon.setTint(p.onSurfaceVariant)
            icon.setBounds(0, 0, dp(24), dp(24))
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(12)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.TRANSPARENT, 4f, stroke = p.outline)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = fillResults(s?.toString().orEmpty())
            })
        }
        resultList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fillResults("")

        val handle = View(this).apply { background = rounded(p.outlineVariant, 2f) }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setGravity(Gravity.CENTER_HORIZONTAL)
            background = GradientDrawable().apply {
                setColor(p.surfaceContainerLow)
                val r = dp(28).toFloat()
                setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
            }
            elevation = dp(1).toFloat()
            isClickable = true
            addView(handle, LinearLayout.LayoutParams(dp(32), dp(4)).apply { setMargins(0, dp(22), 0, dp(22)) })
            addView(searchField, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { setMargins(dp(16), 0, dp(16), dp(8)) })
            addView(ScrollView(context).apply { addView(resultList) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            visibility = View.INVISIBLE
        }
        return sheet
    }

    private fun fillResults(query: String) {
        resultList.removeAllViews()
        plan.search(query)
            .forEach { room -> resultList.addView(resultRow(room), LinearLayout.LayoutParams(MATCH_PARENT, dp(72))) }
    }

    private fun resultRow(room: Room): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        setPadding(dp(16), 0, dp(24), 0)
        background = ripple(ColorDrawable(Color.TRANSPARENT), p.onSurface)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_room)
            imageTintList = ColorStateList.valueOf(p.onSurfaceVariant)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(room.name, 16f, p.onSurface))
            addView(text(subtitle(room), 14f, p.onSurfaceVariant))
        })
        setOnClickListener {
            closeSheet()
            selectRoom(room)
            map.animateFocus(room.bounds)
        }
    }

    private fun openSheet() {
        if (sheetOpen) return
        sheetOpen = true
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(250).start()
        sheet.visibility = View.VISIBLE
        sheet.translationY = sheet.height.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.heightPixels.toFloat()
        sheet.animate().translationY(0f).setDuration(300).start()
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(250)
            .withEndAction { sheet.visibility = View.INVISIBLE }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            building != null && sheetOpen -> closeSheet()
            building != null && map.selectedRoom != null -> selectRoom(null)
            building != null -> closeBuilding()
            campusMap.selected != null -> selectBuilding(null)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CampusCore.release(changingConfigurations = isChangingConfigurations)
    }

    // ---- Small view helpers ----

    private fun subtitle(room: Room) = "${room.category.title} · ${String.format("%.1f", room.area)} м²"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(value: String, sp: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Outlined assist chip with a leading icon. */
    private fun chip(label: String, icon: Int, onClick: () -> Unit) = text(label, 14f, p.onSurface, medium = true).apply {
        gravity = Gravity.CENTER_VERTICAL
        val d = getDrawable(icon)!!.mutate()
        d.setTint(p.primary)
        d.setBounds(0, 0, dp(18), dp(18))
        setCompoundDrawables(d, null, null, null)
        compoundDrawablePadding = dp(8)
        setPadding(dp(8), 0, dp(16), 0)
        background = ripple(rounded(Color.TRANSPARENT, 8f, stroke = p.outlineVariant), p.onSurface)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        setCornerRadius(radiusDp * resources.displayMetrics.density)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(content: Drawable, color: Int): Drawable {
        val rippleColor = (color and 0x00FFFFFF) or 0x1F000000
        val mask = if (content is GradientDrawable) content.constantState?.newDrawable()?.mutate()?.also {
            (it as GradientDrawable).setColor(Color.WHITE)
        } else ColorDrawable(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun iconButton(icon: Int, tint: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = desc
        background = RippleDrawable(ColorStateList.valueOf((tint and 0x00FFFFFF) or 0x1F000000), null, null)
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
    }
}
