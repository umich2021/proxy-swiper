package com.autodate.ui

import android.content.Context
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autodate.data.CaptureStore
import com.autodate.data.Db
import com.autodate.data.ReviewProfile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Offline labels for profiles whose screenshots autoDate already saved. No network or gallery permission. */
class ReviewPage(context: Context) : LinearLayout(context) {

    private data class Row(val profile: ReviewProfile, val images: List<File>)

    private val io = Executors.newSingleThreadExecutor()
    private val loadVersion = AtomicInteger()
    private val imageVersion = AtomicInteger()
    private var rows = listOf<Row>()
    private var index = 0
    private var imageIndex = 0
    private var rendering = false

    private val summary = text("", 14f, Palette.TEXT_2)
    private val empty = text("No saved profile photos yet. Swipe in Learn mode or review a like in Co-pilot first. Photos get deleted after 30 days.", 15f, Palette.TEXT_2)
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val profileName = text("", 20f, Palette.TEXT, bold = true)
    private val position = text("", 13f, Palette.TEXT_2)
    private val actual = text("", 13f, Palette.TEXT_2)
    private val image = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Palette.FIELD)
    }
    private val imagePosition = text("", 13f, Palette.TEXT_2)
    private val previousImage = secondary("‹") { moveImage(-1) }
    private val nextImage = secondary("›") { moveImage(1) }
    private var band: String? = null
    private val bandButtons = mutableListOf<Pair<Button, String>>()
    private val fineRow = LinearLayout(context).apply { orientation = HORIZONTAL; visibility = GONE }
    private val notes = EditText(context).apply {
        hint = "Optional type notes: natural, sharp features, glam..."
        textSize = 15f
        minLines = 2
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.TEXT_2)
        background = Palette.rounded(Palette.FIELD, dp(10).toFloat(), Palette.BORDER, dp(1))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private val photos = ScrollView(context).apply {
        isFillViewport = true
        addView(content)
    }
    private val dock = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(8), 0, 0)
        setBackgroundColor(Palette.BG)
    }

    init {
        orientation = VERTICAL
        addView(summary)
        addView(empty, margin(top = 12))
        addView(photos, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        addView(dock)
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(profileName, LayoutParams(0, -2, 1f))
            addView(position)
        })
        content.addView(actual, margin(top = 4))
        content.addView(image, margin(top = 12))
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previousImage, LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(imagePosition)
            addView(nextImage, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        }, margin(top = 6))
        content.addView(buttonRow(listOf(
            secondary("Previous profile") { moveProfile(-1) },
            secondary("Next profile") { moveProfile(1) },
        )), margin(top = 12))
        content.addView(scoreGuide(), margin(top = 16))
        content.addView(notes, margin(top = 16))
        content.addView(android.view.View(context), LayoutParams(LayoutParams.MATCH_PARENT, dp(160)))
        notes.setOnFocusChangeListener { _, focused -> if (!focused && !rendering) saveNow() }

        dock.addView(buttonRow(listOf("Low", "Med", "High").map { name ->
            compact(name) {
                band = name
                val profile = currentRow()?.profile
                if (profile != null && bandFor(profile.physical) != name && profile.physical != 0) profile.physical = null
                showFine()
            }.also { bandButtons += it to name }
        }))
        dock.addView(fineRow, margin(top = 6))
        dock.addView(primary("Save & next") { nextUnreviewed() }, margin(top = 8))
        dock.addView(secondary("Face unclear") {
            currentRow()?.profile?.physical = 0
            showFine()
            choicesChanged()
        }, margin(top = 6))
    }

    fun reload() {
        val request = loadVersion.incrementAndGet()
        io.execute {
            val loaded = Db(context).use { it.reviewProfiles() }.mapNotNull { p ->
                val images = CaptureStore.profileImages(context, p.key)
                if (images.isEmpty()) null else Row(p, images)
            }
            post {
                if (request != loadVersion.get()) return@post
                val oldKey = currentRow()?.profile?.key
                rows = loaded
                val preserved = rows.indexOfFirst { it.profile.key == oldKey }
                val firstIncomplete = rows.indexOfFirst { !complete(it.profile) }
                index = when {
                    preserved >= 0 -> preserved
                    firstIncomplete >= 0 -> firstIncomplete
                    else -> 0
                }
                render()
            }
        }
    }

    /** Called when leaving the tab or pausing the app, so half-written type notes are not lost. */
    fun saveNow() {
        val p = currentRow()?.profile ?: return
        p.note = notes.text.toString().trim().takeIf { it.isNotEmpty() }
        if (p.physical == null && p.note == null) return
        val snapshot = p.copy()
        io.execute {
            runCatching { Db(context).use { it.saveReview(snapshot) } }
        }
    }

    private fun render() {
        rendering = true
        val row = currentRow()
        empty.visibility = if (row == null) VISIBLE else GONE
        photos.visibility = if (row == null) GONE else VISIBLE
        dock.visibility = if (row == null) GONE else VISIBLE
        updateSummary()
        if (row == null) {
            rendering = false
            return
        }
        val p = row.profile
        profileName.text = p.name.ifBlank { "(no name)" }
        position.text = "${index + 1} of ${rows.size}"
        actual.text = "Just looks here · separate from your Hinge swipe"
        notes.setText(p.note.orEmpty())
        imageIndex = 0
        band = bandFor(p.physical)
        showFine()
        rendering = false
        showImage()
    }

    private fun bandFor(score: Int?) = when (score) {
        in 1..4 -> "Low"
        in 5..7 -> "Med"
        in 8..10 -> "High"
        else -> null
    }

    private fun showFine() {
        bandButtons.forEach { (button, name) -> styleChoice(button, name == band) }
        fineRow.removeAllViews()
        val options = when (band) {
            "Low" -> listOf(1 to "None", 2 to "Almost", 3 to "No", 4 to "Soft")
            "Med" -> listOf(5 to "Neutral", 6 to "Some", 7 to "Yes")
            "High" -> listOf(8 to "Strong", 9 to "Rare", 10 to "Ideal")
            else -> emptyList()
        }
        fineRow.visibility = if (options.isEmpty()) GONE else VISIBLE
        val picked = currentRow()?.profile?.physical
        options.forEachIndexed { i, (score, word) ->
            fineRow.addView(compact("$score $word") {
                currentRow()?.profile?.physical = score
                showFine()
                choicesChanged()
            }.apply { styleChoice(this, picked == score) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
    }

    private fun choicesChanged() {
        updateSummary()
        saveNow()
    }

    private fun updateSummary() {
        val reviewed = rows.count { complete(it.profile) }
        summary.text = if (rows.isEmpty()) {
            "Stays on your phone · no AI calls"
        } else {
            "$reviewed rated · about 40 is enough · ${rows.size} saved"
        }
    }

    private fun showImage() {
        val row = currentRow() ?: return
        imagePosition.text = "Screenshot ${imageIndex + 1} / ${row.images.size}"
        previousImage.isEnabled = imageIndex > 0
        nextImage.isEnabled = imageIndex < row.images.lastIndex
        val file = row.images[imageIndex]
        val request = imageVersion.incrementAndGet()
        io.execute {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            post { if (request == imageVersion.get()) image.setImageBitmap(bitmap) }
        }
    }

    private fun moveImage(delta: Int) {
        val row = currentRow() ?: return
        imageIndex = (imageIndex + delta).coerceIn(0, row.images.lastIndex)
        showImage()
    }

    private fun moveProfile(delta: Int) {
        if (rows.isEmpty()) return
        saveNow()
        index = (index + delta).coerceIn(0, rows.lastIndex)
        render()
    }

    private fun nextUnreviewed() {
        if (rows.isEmpty()) return
        saveNow()
        val candidates = rows.indices.drop(index + 1) + rows.indices.take(index + 1)
        val next = candidates.firstOrNull { !complete(rows[it].profile) }
        if (next == null) {
            toast("All saved profiles are rated")
            return
        }
        if (next == index) {
            toast("Pick a score or Face unclear first")
            return
        }
        index = next
        render()
    }

    private fun currentRow() = rows.getOrNull(index)

    private fun complete(p: ReviewProfile) = p.physical != null

    /** Two compact columns keep the guide short when open; closed by default. */
    private fun scoreGuide() = LinearLayout(context).apply {
        orientation = VERTICAL
        background = Palette.rounded(Palette.SURFACE, dp(12).toFloat(), Palette.BORDER, dp(1))
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
            setPadding(dp(12), 0, dp(12), dp(10))
            val meanings = listOf(
                "1  No attraction" to "6  Some attraction",
                "2  Almost none" to "7  Clear yes",
                "3  Clear no" to "8  Strong yes",
                "4  Soft no" to "9  Exceptional",
                "5  Neutral" to "10  Near-ideal (rare)",
            )
            meanings.forEach { (left, right) ->
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(text(left, 13f, Palette.TEXT_2), LayoutParams(0, -2, 1f))
                    addView(text(right, 13f, Palette.TEXT_2), LayoutParams(0, -2, 1f))
                }, margin(top = 5))
            }
        }
        val arrow = text("▾", 17f, Palette.TEXT_2)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(12), 0, dp(12), 0)
            addView(text("What the numbers mean", 14f, Palette.TEXT, bold = true), LayoutParams(0, -2, 1f))
            addView(arrow)
            setOnClickListener {
                (parent as? ViewGroup)?.let {
                    TransitionManager.beginDelayedTransition(it, AutoTransition().apply { duration = 180 })
                }
                val open = body.visibility != VISIBLE
                body.visibility = if (open) VISIBLE else GONE
                arrow.animate().rotation(if (open) 180f else 0f).setDuration(180).start()
            }
        })
        addView(body)
    }

    private fun buttonRow(buttons: List<Button>) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        buttons.forEachIndexed { i, button ->
            addView(button, LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
    }

    private fun compact(title: String, onClick: () -> Unit) = secondary(title, onClick).apply {
        minWidth = 0
        minimumWidth = 0
        textSize = 13f
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun styleChoice(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Palette.ACCENT else Palette.TEXT)
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.background = Palette.ripple(Palette.rounded(
            if (selected) Palette.ACCENT_SOFT else Palette.SURFACE,
            dp(12).toFloat(),
            if (selected) Palette.ACCENT else Palette.BORDER,
            dp(if (selected) 2 else 1),
        ))
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun primary(title: String, onClick: () -> Unit) = button(title, Palette.ACCENT, Palette.SURFACE, Palette.ACCENT, onClick)

    private fun secondary(title: String, onClick: () -> Unit) = button(title, Palette.SURFACE, Palette.TEXT, Palette.BORDER, onClick)

    private fun button(title: String, fill: Int, textColor: Int, stroke: Int, onClick: () -> Unit) = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        setTextColor(textColor)
        minHeight = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        background = Palette.ripple(Palette.rounded(fill, dp(12).toFloat(), stroke, dp(1)))
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun margin(top: Int) = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        saveNow()
        io.shutdown()
        super.onDetachedFromWindow()
    }
}
