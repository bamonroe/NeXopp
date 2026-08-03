package com.xopp.android.render

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import com.xopp.android.audio.AudioRef
import com.xopp.android.audio.audioRef
import com.xopp.android.audio.withAudio
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Element
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What a canvas tap places when a placement tool is active (see [DrawingSurfaceView.placeKind]). */
enum class PlaceKind { TEXT, IMAGE, TEX }

/** One row of the layer panel: the layer's model index (bottom-up), label, visibility, active flag. */
data class LayerInfo(val index: Int, val label: String, val visible: Boolean, val active: Boolean)

/**
 * Where a placement tap landed: the page and its page-local pt coordinates. [existing] carries the
 * text box the tap hit (so the editor opens it for editing — prefilling its content, font, size and
 * colour — instead of creating a new one); null means "create new".
 */
data class Placement(val pageIndex: Int, val xPt: Double, val yPt: Double, val existing: TextElement? = null)

/**
 * The low-latency stylus canvas. Holds the whole [Document] and renders every page top-to-bottom,
 * each with its background ruling and all its layers, scaled to fit the view width (see
 * [PageStacker] / [BackgroundRenderer]). One finger draws; two fingers scroll the page stack.
 * Strokes are drawn here; text boxes, images, and LaTeX images are drawn by [ElementRenderer].
 * Pressure is captured at the fidelity `.xopp` stores — this is where round-trip safety starts
 * (see `docs/architecture.md`).
 */
class DrawingSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** The working document; edits append strokes to it and [toDocument] returns it verbatim. */
    private var docValue: Document = Document(pages = listOf(blankPage()))

    /**
     * The working document. Assigning it is how every edit lands, so the setter is also the one
     * place a mirrored view can be told the document moved on — see [onDocumentEdited]. Loading a
     * document ([load], [applyMirroredDocument]) writes [docValue] directly instead: that is a view
     * catching up, not an edit, and echoing it back would loop.
     */
    private var doc: Document
        get() = docValue
        set(value) {
            if (value === docValue) return
            docValue = value
            onDocumentEdited?.invoke(value)
        }
    private var layout: StackedLayout = StackedLayout(emptyList(), 0f, 0f)
    private var scrollY = 0f
    private var scrollX = 0f
    private var zoom = 1f
    /** Pages shown side by side: 1 is the single-page stack, 2+ the page-overview grid. */
    private var columns = 1

    /** Rasteriser for the PDF that backs this document's `pdf` pages (set on import), or null. */
    private var pdfSource: PdfPageCache? = null

    /** In-progress stroke (page-local pt space) and the page it belongs to. */
    private var current: ArrayList<StrokePoint>? = null
    private var currentPage = 0
    /** While drawing a shape ([shapeKind] set), the drag anchor in page-local pt and the live flag. */
    private var shaping = false
    private var shapeStartX = 0.0
    private var shapeStartY = 0.0
    /** The spline tool's control points so far (page-local pt); non-empty means one is being laid down. */
    private val splineNodes = ArrayList<SplineNode>()
    /** True between the down and up of a tap that is placing/curving the newest spline node. */
    private var splineDragging = false
    /** Where the pointer went down on the current spline node, so a drag can be read as its tangent. */
    private var splineAnchorX = 0.0
    private var splineAnchorY = 0.0
    /** The previous spline tap's time/position, to recognise the double-tap that finishes the curve. */
    private var splineTapTime = 0L
    private var splineTapX = 0f
    private var splineTapY = 0f
    /** Pointer id owning the current draw/erase gesture, so a resting palm can't perturb it. */
    private var gesturePointerId = -1
    /** True while a stylus/eraser tip owns the current draw/erase gesture (drives palm rejection). */
    private var stylusOwner = false
    // Hover preview position (view px) and whether a stylus is currently hovering.
    private var hovering = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var scrolling = false
    private var erasing = false
    private var placing = false
    private var placeDownX = 0f
    private var placeDownY = 0f
    private var lastFocusY = 0f
    private var lastFocusX = 0f
    /** The two-finger span (mean pointer distance from the focus, view px) at the last pan frame,
     * so a change in span drives a proportional pinch-zoom. 0 means "re-baseline on the next frame". */
    private var lastSpan = 0f

    // Momentum scrolling: a released pan keeps gliding, decelerating, until it stalls or hits a bound.
    private val fling = Fling()
    /** Scales the release velocity fed into a fling; 1 = as-flung, 0 disables momentum. Driven by the
     * momentum-strength setting (see [Momentum]). */
    var flingStrength = Momentum.NORMAL
    /** The velocity→coast response shape for momentum (see [MomentumCurve]); driven by the setting. */
    var momentumCurve = MomentumCurve.QUADRATIC
    /** Scales how far the document moves per unit of pan travel; 1 = one-to-one, 0 freezes it under a
     * pan, >1 pans faster than the finger. Driven by the panning-sensitivity setting (see
     * [PanSensitivity]). Also scales the released velocity so a fling glides at the same visual rate. */
    var panSensitivity = PanSensitivity.NORMAL
    private val choreographer = Choreographer.getInstance()
    private var flinging = false
    private var flingLastFrameNanos = 0L
    /** Release velocity of the just-ended pan (content-space px/s), captured on the final ACTION_UP. */
    private var releaseVx = 0f
    private var releaseVy = 0f
    /** Estimates the pan's velocity from its focus samples so [endGesture] can launch a fling. */
    private val velocityEstimator = VelocityEstimator()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val flingCallback = Choreographer.FrameCallback { frameTimeNanos -> onFlingFrame(frameTimeNanos) }

    /** Set between a [render] request and the vsync that services it — see [render]. */
    private var paintPosted = false
    private val paintCallback = Choreographer.FrameCallback { paint() }

    // Hand-tool double-tap: a centre double-tap toggles full-page view, a left/right-edge double-tap
    // pages back/forward. Detected manually (single-finger tap = down→up without exceeding tap slop).
    private val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlopPx = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    /** The current single touch's down time/position, and whether it has moved past tap slop (→ a pan). */
    private var handTapDownTime = 0L
    private var handTapDownX = 0f
    private var handTapDownY = 0f
    private var handTapCandidate = false
    private var handTapMoved = false
    /** The previous confirmed tap's down time/position, to match the next tap against for a double-tap. */
    private var handFirstTapTime = 0L
    private var handFirstTapX = 0f
    private var handFirstTapY = 0f

    // Page-overview drag-to-reorder: in the multi-column grid a finger long-press lifts a page and the
    // drag drops it at another slot. Armed on touch-down, fired by [pageDragArm] after the long-press
    // timeout; see [startPageDrag].
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** The page being dragged in the overview, or -1 when no reorder drag is in flight. */
    private var pageDragIndex = -1
    /** Where the lifted page would land on release, or -1. */
    private var pageDropIndex = -1
    /** True between touch-down and the long-press firing (or the touch disqualifying itself). */
    private var pageDragArmed = false
    private var pageDragDownX = 0f
    private var pageDragDownY = 0f
    private val pageDragArm = Runnable { startPageDrag() }

    /**
     * Is the overview grid in edit mode? In view mode (the default) the grid is purely a way to look
     * at and navigate the document — a tap jumps to a page, and there is no selection or reordering.
     * Edit mode turns on tap-to-select, drag-to-reorder and the copy/delete tooling. View-only state.
     */
    private var pagesEditMode = false

    /** Pages picked in the overview grid (by index), the set a delete acts on. View-only state. */
    private val selectedPages = mutableSetOf<Int>()

    /** Pages copied out of the overview, awaiting a paste. View-only state; pages are immutable. */
    private var pageClipboard: List<Page> = emptyList()

    /** The text box a placement tap hit, awaiting an edit-or-delete from the editor. */
    private var editingTarget: TextElement? = null
    /** Which page index was last reported to [onCurrentPageChanged], to suppress duplicate calls. */
    private var lastReportedPage = -1
    /** Last (scrollY, totalHeightPx, viewportPx) reported to [onScrollChanged], to suppress duplicate calls. */
    private var lastScrollReport = Triple(-1f, -1f, -1f)

    /** Undo/redo snapshots of the whole [Document] (cheap: immutable pages/layers share structure). */
    private val history = EditHistory<Document>()
    /** The document as it was when the current gesture began, so one gesture is one undo step. */
    private var gestureStartDoc: Document? = null

    /** Notified with (canUndo, canRedo) whenever the history changes, so the chrome can enable buttons. */
    var onHistoryChanged: ((Boolean, Boolean) -> Unit)? = null
    /**
     * Notified with the new document after every edit made on this canvas, so another view of the
     * same document (the split's other pane) can be handed it — see [applyMirroredDocument].
     */
    var onDocumentEdited: ((Document) -> Unit)? = null
    /** Notified with the current zoom factor whenever it changes, so the chrome can show the level. */
    var onZoomChanged: ((Float) -> Unit)? = null
    /** Notified with the page-overview column count whenever it changes. */
    var onColumnsChanged: ((Int) -> Unit)? = null
    /** Notified with the page count whenever it changes (load, add, remove). */
    var onPageCountChanged: ((Int) -> Unit)? = null
    /** Notified with the index of the page nearest the viewport centre whenever it changes. */
    var onCurrentPageChanged: ((Int) -> Unit)? = null
    /** Notified with how many overview pages are selected whenever the selection changes. */
    var onPageSelectionChanged: ((Int) -> Unit)? = null
    /** Notified with how many pages are on the page clipboard whenever a copy changes it. */
    var onPageClipboardChanged: ((Int) -> Unit)? = null
    /** Notified with (scrollY, totalHeightPx, viewportPx) whenever the vertical scroll or content extent changes — drives the right-edge scroll thumb. */
    var onScrollChanged: ((Float, Float, Float) -> Unit)? = null
    /** Notified when a placement tap lands, so the editor can prompt for content / pick an image. */
    var onPlace: ((PlaceKind, Placement) -> Unit)? = null
    /** Notified when the Hand tool receives a centre double-tap, so the editor can toggle full-page (chrome-hidden) view. */
    var onToggleFullPage: (() -> Unit)? = null

    var tool: Tool = Tool.PEN
    var colorArgb: Int = AndroidColor.BLACK
    var baseWidthPt: Float = 1.5f
    /** Input-layer settings (finger-draw / barrel action) consulted by [InputClassifier]; from Settings. */
    var inputSettings: InputSettings = InputSettings()
    /** Pressure→width exponent (see [PressureCurve]); 1 = linear. Set from the sensitivity setting. */
    var pressureGamma: Float = PressureSensitivity.LINEAR.gamma
    /** Jitter filter for the in-progress freehand stroke; reset at the start of each stroke. */
    private val smoother = StrokeSmoother()
    /** How much digitiser detail freehand strokes keep (see [StrokePrecision]); from Settings. */
    // The decimation radius depends on the page's px/pt as well as this setting, so it is computed
    // per stroke in [startStroke] rather than pinned here.
    var strokePrecision: StrokePrecision = StrokePrecision.DEFAULT
    /** When true, a hovering stylus shows a preview dot (from `ACTION_HOVER_MOVE`). */
    var showHover: Boolean = true
    /** When true, one finger pans the canvas (the Hand tool) instead of drawing/erasing. */
    var handMode: Boolean = false
    /** When non-null, a one-finger tap places an element of this kind instead of drawing. */
    var placeKind: PlaceKind? = null
    /** When true, one finger rubber-band-selects (or moves an existing selection) instead of drawing. */
    var selectMode: Boolean = false
        set(value) {
            field = value
            if (!value) clearSelection()
        }

    /** When true, a drag inserts/removes vertical space on a page instead of drawing (see [VerticalSpaceOps]). */
    var verticalSpaceMode: Boolean = false

    /** When true, a one-finger tap replays the tapped stroke's recording instead of drawing. */
    var audioPlayMode: Boolean = false

    /**
     * Consulted the instant a stroke is committed: returns the recording position to stamp onto it
     * (`fn`/`ts`), or null when nothing is recording. Reading it at commit rather than at the start
     * of the gesture is deliberate — it keeps the whole audio machinery out of the drawing hot path.
     */
    var audioStamp: (() -> AudioRef?)? = null

    /** Notified when an [audioPlayMode] tap lands, with the tapped stroke's link (null if it has none). */
    var onAudioTap: ((AudioRef?) -> Unit)? = null

    /** When true, the Select tool's marquee is a free-form lasso instead of a rectangle. */
    var lassoMode: Boolean = false

    /**
     * When non-null, a one-finger drag draws this geometric shape instead of a freehand stroke.
     * [ShapeKind.SPLINE] is the exception: it is laid down tap-by-tap, so switching away from it
     * commits whatever curve is still open rather than silently dropping it.
     */
    var shapeKind: ShapeKind? = null
        set(value) {
            if (field == ShapeKind.SPLINE && value != ShapeKind.SPLINE) finishSpline()
            field = value
        }
    /**
     * When true, a finished freehand pen stroke that clearly means a primitive (line, arrow, circle,
     * rectangle, triangle, polyline) is snapped to clean geometry — see [ShapeRecognizer].
     */
    var recognizeShapes: Boolean = false

    /** When true, a shape tool's start/end points snap to the page background's ruling ([Snapping]). */
    var snapToGrid: Boolean = false

    /** When true, dragging the selection's rotate handle steps in [Snapping.ROTATION_STEP_DEG]. */
    var snapRotation: Boolean = false

    /**
     * The on-canvas setsquare/compass overlay, or null when none is placed. It is an input aid only
     * — it constrains drawn vertices (see [guided]) and is never written to the document.
     */
    var guide: DrawingGuide? = null
        private set

    /** The page the [guide]'s pose is expressed in; the guide only constrains strokes on that page. */
    private var guidePage: Int = 0

    /** Notified whenever the user moves or re-poses the guide, so the pose can be remembered. */
    var onGuideChanged: ((DrawingGuide?) -> Unit)? = null

    /** Line pattern applied to strokes and shapes this tool draws (dashed/dotted); default solid. */
    var currentLineStyle: LineStyle = LineStyle.PLAIN
    /** Fill alpha (0..255) flooded inside strokes/shapes drawn now, or null for no fill. */
    var currentFill: Int? = null
    /** How the eraser removes ink: [EraserMode.STANDARD] rubs out touched segments; [EraserMode.WHOLE_STROKE] deletes whole strokes. */
    var eraserMode: EraserMode = EraserMode.STANDARD
    /**
     * The eraser tip size follows the pen's: it is derived from [baseWidthPt] via [eraserRadiusPt],
     * so the rail's Size popup sizes the pen and the rubber together (there is no separate scheme).
     * The radius is in document pt, so the tip covers the same ink at any zoom.
     */
    val eraserRadiusPt: Double get() = eraserRadiusPt(baseWidthPt)

    /** Layer new ink lands on for the visible page; -1 = the top layer. Resolved/clamped per page. */
    var activeLayerIndex: Int = -1
        private set
    /** `(pageIndex, layerIndex)` keys hidden in the editor only — view state, never persisted. */
    private val hiddenLayers = HashSet<Long>()
    /** Notified when the layer set / active layer / visibility changes so the chrome can refresh. */
    var onLayersChanged: (() -> Unit)? = null

    /** Notified whenever the selection appears or clears, so the chrome can show contextual actions. */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    /** Notified when the copy/cut clipboard gains or loses content (drives the Paste affordance). */
    var onClipboardChanged: ((Boolean) -> Unit)? = null

    /** The current selection (a page index + the refs of its selected elements), or null. */
    private var selection: ActiveSelection? = null

    /** Copied/cut elements, ready to paste onto the visible page. */
    private var clipboard: List<Element> = emptyList()

    // Live rubber-band marquee (view px) and the page it selects within.
    private var banding = false
    private var bandPage = 0
    private var bandX0 = 0f
    private var bandY0 = 0f
    private var bandX1 = 0f
    private var bandY1 = 0f
    // Free-form lasso path (view px, x/y interleaved) captured while banding in lasso mode.
    private val lassoPts = ArrayList<Float>()

    /** When true, a one-finger/stylus drag selects text over an imported PDF's extracted text layer. */
    var textSelectMode: Boolean = false
        set(value) {
            field = value
            if (!value) clearTextSelection()
        }

    /** The imported PDF's positioned text layer, or null when no PDF (or no text) is loaded. */
    private var pdfTextIndex: PdfTextIndex? = null

    /** Notified when a PDF-text selection appears or clears, so the chrome can offer Copy. */
    var onTextSelectionChanged: ((Boolean) -> Unit)? = null

    // Live/committed PDF-text selection: a page and an inclusive reading-order word range.
    private var textSelecting = false
    private var textSelPage = -1
    private var textSelAnchor = -1
    private var textSelFocus = -1

    // Live move of the current selection (also the base snapshot for resize/rotate, so a live
    // transform recomputes from the gesture-start document each frame and never drifts).
    private var movingSel = false
    private var moveStartDoc: Document? = null
    private var moveStartPtX = 0.0
    private var moveStartPtY = 0.0

    // Live vertical-space drag: the grabbed page, the grab line (page pt) and its view-px Y for the
    // guide overlay. Like a selection move, each frame recomputes from the gesture-start snapshot.
    private var vspacing = false
    private var vspacePage = 0
    private var vspaceLinePt = 0.0
    private var vspaceLineViewY = 0f

    // Live drag of the setsquare/compass overlay: which part is held, and the grab offset from the
    // guide's anchor so the body slides without jumping under the finger.
    private var guideDrag = GUIDE_DRAG_NONE
    private var guidePointerId = -1
    private var pendingGuide: GuideKind? = null
    private var guideGrabDx = 0.0
    private var guideGrabDy = 0.0

    // Live uniform resize about a fixed anchor (the opposite corner), in page-local pt.
    private var resizing = false
    private var resizeAnchorX = 0.0
    private var resizeAnchorY = 0.0
    private var resizeStartDist = 1.0

    // Live rotate about the selection centre (pt); strokes only.
    private var rotating = false
    private var rotatePivotX = 0.0
    private var rotatePivotY = 0.0
    private var rotateStartAngle = 0.0

    private val strokePainter = StrokePainter()
    private val elementRenderer = ElementRenderer()

    /** Off-screen ink rasters, so a pan/fling frame blits pages instead of re-submitting strokes. */
    private val inkCache = InkCache()

    /** Set while a coalesced redraw is queued — see [requestRender]. */
    private val renderPosted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Backdrop behind the pages — replaced by the app's colour scheme via [applyChromeColors]. */
    private var backdropColor = BACKDROP

    private val selectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = SELECTION_COLOR
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    // The setsquare/compass overlay: a solid outline (it is a physical-feeling instrument, not a
    // marquee, so no dashes) over a faint wash, plus filled grab handles.
    private val guideStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = GUIDE_COLOR
    }
    // Page-overview reorder feedback: the lifted page is washed out, the drop slot outlined.
    private val pageLiftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66FFFFFF
    }
    private val pageDropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = GUIDE_COLOR
    }
    // Page-overview multi-select: a picked page is tinted and outlined so the selection reads at a glance.
    private val pageSelectFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (GUIDE_COLOR and 0x00FFFFFF) or (0x33 shl 24)
    }
    private val pageSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = GUIDE_COLOR
    }
    private val guideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (GUIDE_COLOR and 0x00FFFFFF) or (GUIDE_FILL_ALPHA shl 24)
    }
    private val guideHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GUIDE_COLOR
    }
    private val guidePath = Path()
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTION_FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTION_COLOR
    }
    private val handleArmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = SELECTION_COLOR
    }
    /**
     * Repaints the canvas chrome from the app's Material 3 colour scheme. The `SurfaceView` sits
     * outside the Compose tree, so the colours are pushed in from the hosting composable; see
     * `com.xopp.android.ui.theme.CanvasChromeColors`. The translucent fills keep their original
     * alphas so the wash stays faint enough to draw over.
     */
    fun applyChromeColors(backdrop: Int, selection: Int, guide: Int) {
        backdropColor = backdrop
        selectionStrokePaint.color = selection
        handlePaint.color = selection
        handleArmPaint.color = selection
        selectionFillPaint.color = selection.withAlpha(SELECTION_FILL_ALPHA)
        bandFillPaint.color = selection.withAlpha(BAND_FILL_ALPHA)
        guideStrokePaint.color = guide
        guideHandlePaint.color = guide
        guideFillPaint.color = guide.withAlpha(GUIDE_FILL_ALPHA)
        requestRender()
    }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

    private val lassoPath = Path()
    private val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BAND_FILL
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = TEXT_SELECT_FILL
    }

    init {
        holder.addCallback(this)
    }

    /** Replace the canvas contents with [doc] (all pages, layers, and unmodelled elements). */
    fun load(doc: Document) {
        this.docValue = if (doc.pages.isEmpty()) doc.copy(pages = listOf(blankPage())) else doc
        scrollY = 0f
        scrollX = 0f
        selection = null
        onSelectionChanged?.invoke(false)
        hiddenLayers.clear()
        activeLayerIndex = -1
        history.clear()
        notifyHistory()
        onPageCountChanged?.invoke(this.doc.pages.size)
        lastReportedPage = -1
        relayout()
        render()
        onLayersChanged?.invoke()
    }

    /**
     * Adopt [incoming] as the document *without* disturbing this view: the scroll position, zoom and
     * column count are left exactly as they are, so a mirrored pane can sit on a different page of
     * the same document while it updates under you.
     *
     * The undo history is dropped, because it holds snapshots taken before the other view's edit and
     * undoing to one would silently throw that edit away. Undo therefore lives in the pane doing the
     * editing, and follows the user as they switch panes.
     */
    fun applyMirroredDocument(incoming: Document) {
        if (incoming === docValue) return
        docValue = if (incoming.pages.isEmpty()) incoming.copy(pages = listOf(blankPage())) else incoming
        current = null
        gestureStartDoc = null
        selection = null
        onSelectionChanged?.invoke(false)
        history.clear()
        notifyHistory()
        onPageCountChanged?.invoke(docValue.pages.size)
        relayout()
        render()
        onLayersChanged?.invoke()
    }

    /** The current working document — every page, layer, and preserved element, ready to save. */
    fun toDocument(): Document = doc

    /**
     * Supply the PDF whose pages back this document's `pdf` backgrounds (set on import), or null to
     * clear it (opening a plain `.xopp`). Closes any previously-held rasteriser.
     */
    fun setPdfSource(source: PdfPageCache?) {
        if (source === pdfSource) return
        pdfSource?.onPageReady = null
        pdfSource?.close()
        pdfSource = source
        // Rasterisation runs on a worker; redraw once a sharper page lands. A pan can land a dozen
        // tiles in one frame's time, so the redraws are coalesced into a single pass.
        source?.onPageReady = { requestRender() }
    }

    /** Supply the imported PDF's extracted text layer for the text-select tool, or null to clear it. */
    fun setPdfTextIndex(index: PdfTextIndex?) {
        pdfTextIndex = index
        clearTextSelection()
    }

    /** True when the PDF has a usable text layer, so the chrome can enable the text-select tool. */
    fun hasPdfText(): Boolean = pdfTextIndex?.hasAnyText == true

    /** The on-disk PDF backing this document's `pdf` backgrounds, or null when there is none. */
    fun pdfSourceFile(): java.io.File? = pdfSource?.source

    /** How many pages the backing PDF has — where an appended PDF's pages start once merged in. */
    fun pdfSourcePageCount(): Int = pdfSource?.pageCount ?: 0

    /** Flatten the current document (backgrounds, PDF pages, and all annotations) to a PDF. */
    fun exportPdf(out: java.io.OutputStream) = PdfExporter(pdfSource).export(doc, out)

    // --- undo / redo ---------------------------------------------------------------------------

    /** Revert the most recent edit (stroke or erase gesture). No-op when there's nothing to undo. */
    fun undo() {
        doc = history.undo(doc) ?: return
        afterHistoryMove()
    }

    /** Re-apply the most recently undone edit. No-op when there's nothing to redo. */
    fun redo() {
        doc = history.redo(doc) ?: return
        afterHistoryMove()
    }

    private fun afterHistoryMove() {
        current = null
        relayout()
        render()
        notifyHistory()
    }

    private fun notifyHistory() {
        onHistoryChanged?.invoke(history.canUndo, history.canRedo)
    }

    // --- page overview -------------------------------------------------------------------------

    /**
     * Show [n] pages side by side (1 = the plain single-page stack, 2+ = the page overview grid).
     * The current page is kept in view, so zooming out to the grid and back doesn't lose your place.
     */
    fun setColumns(n: Int) {
        val next = n.coerceIn(1, PageStacker.COLUMN_CHOICES.last())
        if (next == columns) return
        val at = currentPageIndex()
        cancelPageDrag() // leaving (or reshaping) the grid abandons any lift in flight
        if (next == 1) clearPageSelection() // no grid, no way to see or extend a selection
        columns = next
        relayout()
        goToPage(at)
        onColumnsChanged?.invoke(columns)
        render()
    }

    fun columns(): Int = columns

    // --- zoom ----------------------------------------------------------------------------------

    fun zoomIn() = setZoom(zoom * ZOOM_STEP)
    fun zoomOut() = setZoom(zoom / ZOOM_STEP)
    fun resetZoom() = setZoom(1f)

    /** Set the zoom factor (clamped), keeping the point at the viewport centre roughly fixed. */
    private fun setZoom(target: Float) {
        val next = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val yFrac = if (layout.totalHeightPx > 0f) (scrollY + height / 2f) / layout.totalHeightPx else 0f
        val xFrac = if (layout.contentWidthPx > 0f) (scrollX + width / 2f) / layout.contentWidthPx else 0f
        zoom = next
        relayout()
        scrollY = (yFrac * layout.totalHeightPx - height / 2f).coerceIn(0f, maxScrollY())
        scrollX = (xFrac * layout.contentWidthPx - width / 2f).coerceIn(0f, maxScrollX())
        render()
        onZoomChanged?.invoke(zoom)
    }

    /**
     * Multiply the zoom by [factor] (clamped) while keeping the content point under the viewport
     * pixel ([focusVx], [focusVy]) fixed — the anchor for pinch-zoom. Unlike [setZoom] this does not
     * render; the pan frame that drives it renders once at the end. No-op if the clamp bites.
     */
    private fun zoomAbout(focusVx: Float, focusVy: Float, factor: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        // Work in zoom-invariant fractions so the anchor survives the relayout (layout px scale with zoom).
        val xFrac = if (layout.contentWidthPx > 0f) (scrollX + focusVx) / layout.contentWidthPx else 0f
        val yFrac = if (layout.totalHeightPx > 0f) (scrollY + focusVy) / layout.totalHeightPx else 0f
        zoom = next
        relayout()
        scrollX = (xFrac * layout.contentWidthPx - focusVx).coerceIn(0f, maxScrollX())
        scrollY = (yFrac * layout.totalHeightPx - focusVy).coerceIn(0f, maxScrollY())
        onZoomChanged?.invoke(zoom)
    }

    // --- pages ---------------------------------------------------------------------------------

    /** Insert a blank page after the page in view (see [PageOps.addAfter]: keeps size and solid ruling,
     *  drops a PDF/pixmap background to a plain sheet so it isn't a duplicate). */
    fun addPage() {
        val at = currentPageIndex()
        editPages(PageOps.addAfter(doc.pages, at))
    }

    /**
     * Append [pages] after the document's existing pages as one undoable edit — the "Append" half of
     * PDF import (see [PageOps.appendPages]). Unlike [load] this keeps the current annotations and
     * undo history, so an accidental import is one undo away.
     */
    fun appendPages(pages: List<Page>) = editPages(PageOps.appendPages(doc.pages, pages))

    /**
     * Append [pages] *and* re-point the document's PDF background reference at [reference] in one
     * undoable edit — appending a second PDF, which merges into a single joined background PDF (see
     * [PdfMerger]). Both halves have to move together: the appended pages' `pageno` values index the
     * joined PDF, so a document holding the old reference with the new pages wouldn't round-trip.
     */
    fun appendPdfPages(pages: List<Page>, reference: String) {
        val retargeted = documentWithPdfReference(doc, reference, ABSOLUTE_DOMAIN)
        editPages(PageOps.appendPages(retargeted.pages, pages))
    }

    /** True when any page is backed by an imported PDF — i.e. the document already has a PDF source. */
    fun hasPdfBackground(): Boolean = doc.pages.any { it.background is Background.Pdf }

    /** Delete the page currently in view. No-op when only one page remains. */
    fun removePage() {
        val at = currentPageIndex()
        editPages(PageOps.removeAt(doc.pages, at))
    }

    // --- page-overview selection -----------------------------------------------------------------
    // In the multi-column grid a Hand-tool finger tap picks pages out; the picked set is what
    // [deleteSelectedPages] removes in one undoable edit. Purely view state — nothing is written to
    // the `.xopp` until a delete commits.

    /**
     * Turn overview editing on or off. Leaving edit mode abandons any lift in flight and clears the
     * selection, so coming back to the grid to read never has stale selection chrome on it.
     */
    fun setPagesEditMode(on: Boolean) {
        if (on == pagesEditMode) return
        pagesEditMode = on
        if (!on) { cancelPageDrag(); clearPageSelection() }
        render()
    }

    fun pagesEditMode(): Boolean = pagesEditMode

    /** How many overview pages are currently selected. */
    fun selectedPageCount(): Int = selectedPages.size

    /** Drop the overview selection (e.g. on leaving the grid, or after a delete). */
    fun clearPageSelection() {
        if (selectedPages.isEmpty()) return
        selectedPages.clear()
        onPageSelectionChanged?.invoke(0)
        render()
    }

    /** Add or remove page [index] from the overview selection. */
    private fun togglePageSelection(index: Int) {
        if (index !in doc.pages.indices) return
        if (!selectedPages.remove(index)) selectedPages.add(index)
        onPageSelectionChanged?.invoke(selectedPages.size)
        render()
    }

    /**
     * Delete every selected page as one undoable edit and clear the selection. A selection covering
     * the whole document is refused by [PageOps.removeAll] (a document always keeps a page), so
     * nothing is deleted in that case.
     */
    fun deleteSelectedPages() {
        val pages = PageOps.removeAll(doc.pages, selectedPages)
        clearPageSelection()
        editPages(pages)
    }

    /**
     * Put the selected pages on the page clipboard, in document order. Nothing is edited yet — the
     * clipboard is view state that survives until the next copy (or the editor closing), so a copy can
     * be pasted repeatedly. The selection is left alone so "copy, then paste after these" reads
     * naturally.
     */
    fun copySelectedPages() {
        val copied = PageOps.copyOf(doc.pages, selectedPages)
        if (copied.isEmpty()) return
        pageClipboard = copied
        onPageClipboardChanged?.invoke(copied.size)
    }

    /** How many pages are on the page clipboard (0 when nothing has been copied). */
    fun copiedPageCount(): Int = pageClipboard.size

    /**
     * Paste the clipboard pages as one undoable edit, directly after the last selected page — or after
     * the page nearest the viewport centre when nothing is selected. The pasted pages carry their
     * strokes, layers, size and background verbatim (see [PageOps.copyOf]), so they round-trip.
     */
    fun pasteCopiedPages() {
        if (pageClipboard.isEmpty()) return
        val after = selectedPages.maxOrNull() ?: currentPageIndex()
        val at = after.coerceIn(0, doc.pages.lastIndex.coerceAtLeast(0))
        editPages(PageOps.insertAfter(doc.pages, at, pageClipboard))
        goToPage(at + 1)
    }

    /**
     * The visible page's background style (plain/lined/ruled/graph/dotted), or null when the page
     * isn't a solid sheet (PDF/pixmap) and so has no selectable paper style.
     */
    fun visiblePageBackgroundStyle(): String? =
        (doc.pages.getOrNull(visiblePageIndex())?.background as? Background.Solid)?.style

    /**
     * Set the visible page's paper [style] (plain/lined/ruled/graph/dotted) as one undoable edit.
     * No-op on PDF/pixmap pages, whose background isn't a solid sheet.
     */
    fun setPageBackgroundStyle(style: String) = editVisiblePage(resetViewState = false, op = { page ->
        val bg = page.background
        if (bg is Background.Solid && bg.style != style) page.copy(background = bg.copy(style = style)) else page
    })

    /** The visible page's size in points (width to height), or null when the document has no pages. */
    fun visiblePageSize(): Pair<Double, Double>? =
        doc.pages.getOrNull(visiblePageIndex())?.let { it.width to it.height }

    /**
     * Set the visible page's size to [widthPt] × [heightPt] points as one undoable edit; both are
     * clamped to a sane range. The stacked layout re-fits every page to the view width, so this
     * changes the page's on-screen aspect ratio (and the dimensions written to the `.xopp`).
     */
    fun setPageSize(widthPt: Double, heightPt: Double) = editVisiblePage(resetViewState = false, op = { page ->
        val w = widthPt.coerceIn(PAGE_SIZE_MIN_PT, PAGE_SIZE_MAX_PT)
        val h = heightPt.coerceIn(PAGE_SIZE_MIN_PT, PAGE_SIZE_MAX_PT)
        if (page.width == w && page.height == h) page else page.copy(width = w, height = h)
    })

    /** Apply a new page list as one undoable edit, if it actually differs. */
    private fun editPages(pages: List<Page>) {
        if (pages === doc.pages) return
        val before = doc
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        onPageCountChanged?.invoke(pages.size)
        // Page indices shifted: view state keyed by page index is no longer valid.
        clearPageSelection()
        hiddenLayers.clear()
        activeLayerIndex = -1
        // keep the viewport valid, then keep zoom's centre-fraction sane
        relayout()
        scrollY = scrollY.coerceIn(0f, maxScrollY())
        render()
        onLayersChanged?.invoke()
    }

    /** Index of the page nearest the viewport centre — the one add/remove act on. */
    private fun currentPageIndex(): Int =
        layout.nearestPage(scrollX + width / 2f, scrollY + height / 2f)?.index ?: doc.pages.lastIndex.coerceAtLeast(0)

    // --- layers --------------------------------------------------------------------------------
    // The panel acts on the page nearest the viewport centre ([visiblePageIndex]); layer indices are
    // bottom-up (0 = bottom z-order, last = top), matching the model. Visibility and the active layer
    // are view-only editor state; add/delete/rename/reorder/move mutate the document (undoable).

    private fun layerKey(pi: Int, li: Int): Long = (pi.toLong() shl 32) or (li.toLong() and 0xFFFFFFFFL)
    private fun isLayerHidden(pi: Int, li: Int): Boolean = layerKey(pi, li) in hiddenLayers

    /** The visible page's layers, bottom-up, as UI-facing rows (label / visible / active). */
    fun visibleLayers(): List<LayerInfo> {
        val pi = visiblePageIndex()
        val page = doc.pages.getOrNull(pi) ?: return emptyList()
        val active = resolvedActiveLayer(page)
        return page.layers.indices.map { li ->
            LayerInfo(li, LayerOps.label(page, li), !isLayerHidden(pi, li), li == active)
        }
    }

    /** Apply [op] to the visible page's layers as one undoable edit; [after] runs post-commit. */
    private fun editVisiblePage(resetViewState: Boolean, op: (Page) -> Page, after: () -> Unit = {}) {
        val before = doc
        val pi = visiblePageIndex()
        val page = doc.pages.getOrNull(pi) ?: return
        val newPage = op(page)
        if (newPage === page) { after(); onLayersChanged?.invoke(); return }
        if (resetViewState) hiddenLayers.clear()
        val pages = doc.pages.toMutableList().also { it[pi] = newPage }
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        after()
        relayout()
        // A page's height may have changed (e.g. a resize), so keep the viewport in range.
        scrollY = scrollY.coerceIn(0f, maxScrollY())
        render()
        onLayersChanged?.invoke()
    }

    /** Add a fresh empty layer above the top and make it active. */
    fun addLayer() = editVisiblePage(resetViewState = true, op = {
        val (p, _) = LayerOps.add(it); p
    }, after = { activeLayerIndex = -1 /* -1 already resolves to the new top */ })

    /** Delete layer [index] (never the last remaining layer). */
    fun deleteLayer(index: Int) = editVisiblePage(resetViewState = true, op = { LayerOps.remove(it, index) })

    /** Rename layer [index] ([name] blank clears the custom name). */
    fun renameLayer(index: Int, name: String) =
        editVisiblePage(resetViewState = false, op = { LayerOps.rename(it, index, name) })

    /** Reorder layer [from] to position [to] (changes z-order). */
    fun moveLayer(from: Int, to: Int) =
        editVisiblePage(resetViewState = true, op = { LayerOps.move(it, from, to) })

    /** Make layer [index] the one new ink lands on (view state; no document change). */
    fun setActiveLayer(index: Int) {
        activeLayerIndex = index
        onLayersChanged?.invoke()
    }

    /** Show/hide layer [index] in the editor only (view state; content still round-trips). */
    fun setLayerHidden(index: Int, hidden: Boolean) {
        val key = layerKey(visiblePageIndex(), index)
        if (hidden) hiddenLayers += key else hiddenLayers -= key
        render()
        onLayersChanged?.invoke()
    }

    /** Move the current selection onto layer [index] (undoable), keeping it selected there. */
    fun moveSelectionToLayer(index: Int) {
        val sel = selection ?: return
        if (sel.pageIndex != visiblePageIndex()) return
        editVisiblePage(resetViewState = false, op = { page ->
            val (newPage, refs) = LayerOps.moveElementsToLayer(page, sel.refs, index)
            selection = if (refs.isEmpty()) null else ActiveSelection(sel.pageIndex, refs)
            newPage
        }, after = { onSelectionChanged?.invoke(selection != null) })
    }

    /** Scroll so page [index]'s top aligns with the viewport top (used by the page navigator). */
    fun goToPage(index: Int) {
        val box = layout.boxes.getOrNull(index) ?: return
        scrollY = box.topPx.coerceIn(0f, maxScrollY())
        render()
    }

    /** Set the vertical scroll offset to [y] px from the top, clamped (driven by the right-edge scroll thumb). */
    fun scrollToY(y: Float) {
        scrollY = y.coerceIn(0f, maxScrollY())
        render()
    }

    /** Emit [onCurrentPageChanged] if the page under the viewport centre changed since last time. */
    private fun reportCurrentPage() {
        if (doc.pages.isEmpty()) return
        val idx = currentPageIndex()
        if (idx != lastReportedPage) {
            lastReportedPage = idx
            onCurrentPageChanged?.invoke(idx)
            onLayersChanged?.invoke() // the panel tracks the visible page's layers
        }
    }

    /** Emit [onScrollChanged] if the scroll offset or content extent changed since last time. */
    private fun reportScroll() {
        val t = Triple(scrollY, layout.totalHeightPx, height.toFloat())
        if (t != lastScrollReport) {
            lastScrollReport = t
            onScrollChanged?.invoke(t.first, t.second, t.third)
        }
    }

    // --- authoring: place text boxes, images, and LaTeX images by tapping ------------------------

    /** Create a text box (or edit the one a tap hit) at the placement; blank content deletes it. */
    fun insertText(p: Placement, content: String, font: String, sizePt: Double, colorArgb: Int) {
        val target = editingTarget
        editingTarget = null
        if (content.isBlank()) {
            if (target != null) replaceElement(target, null)
            return
        }
        val text = TextElement(font, sizePt, p.xPt, p.yPt, colorArgb, content)
        if (target != null) replaceElement(target, text) else addElement(p.pageIndex, text)
    }

    /** Place a LaTeX image at the placement, sized to a default box (resizable later). */
    fun insertTex(p: Placement, latex: String, colorArgb: Int) {
        if (latex.isBlank()) return
        addElement(p.pageIndex, TexImageElement(p.xPt, p.yPt, p.xPt + TEX_W_PT, p.yPt + TEX_H_PT, latex, colorArgb))
    }

    /** Place an encoded image (PNG/JPEG bytes) at the placement, scaled to fit a default extent. */
    fun insertImage(p: Placement, data: ByteArray) {
        val (wPt, hPt) = imageBoxPt(data)
        addElement(p.pageIndex, ImageElement(p.xPt, p.yPt, p.xPt + wPt, p.yPt + hPt, data))
    }

    /** Discard a pending text-edit target (the editor's dialog was dismissed without saving). */
    fun cancelTextEdit() { editingTarget = null }

    /** The natural pt size for an image, scaled so its longest side is [IMG_MAX_PT]. */
    private fun imageBoxPt(data: ByteArray): Pair<Double, Double> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val w = opts.outWidth.coerceAtLeast(1)
        val h = opts.outHeight.coerceAtLeast(1)
        val s = IMG_MAX_PT / maxOf(w, h)
        return w * s to h * s
    }

    /** Append [element] to the top layer of page [pageIndex] as one undoable edit. */
    private fun addElement(pageIndex: Int, element: Element) {
        val before = doc
        val pages = doc.pages.toMutableList()
        val page = pages.getOrNull(pageIndex) ?: return
        val layers = page.layers.ifEmpty { listOf(Layer(emptyList())) }.toMutableList()
        val target = resolvedActiveLayer(page).coerceIn(0, layers.lastIndex)
        layers[target] = Layer(layers[target].elements + element, layers[target].name)
        pages[pageIndex] = page.copy(layers = layers)
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** Replace [old] (matched by identity) with [new], or remove it when [new] is null; undoable. */
    private fun replaceElement(old: Element, new: Element?) {
        val before = doc
        var changed = false
        val pages = doc.pages.map { page ->
            page.copy(layers = page.layers.map { layer ->
                val idx = layer.elements.indexOfFirst { it === old }
                if (idx < 0) return@map layer
                changed = true
                val els = layer.elements.toMutableList()
                if (new == null) els.removeAt(idx) else els[idx] = new
                Layer(els, layer.name)
            })
        }
        if (!changed) return
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** The top-most text box whose (approximate) bounds contain the point, or null. */
    private fun pickText(pageIndex: Int, xPt: Double, yPt: Double): TextElement? {
        val page = doc.pages.getOrNull(pageIndex) ?: return null
        for (layer in page.layers.asReversed()) {
            for (el in layer.elements.asReversed()) {
                if (el is TextElement && hitsText(el, xPt, yPt)) return el
            }
        }
        return null
    }

    /** Rough hit test for a text box from its content extent (glyph widths aren't measured here). */
    private fun hitsText(t: TextElement, xPt: Double, yPt: Double): Boolean {
        val lines = t.content.split("\n")
        val h = lines.size * t.size * 1.3
        val w = (lines.maxOfOrNull { it.length } ?: 1) * t.size * 0.62
        return xPt >= t.x - 4 && xPt <= t.x + w + 4 && yPt >= t.y - 4 && yPt <= t.y + h + 4
    }

    // --- selection: rubber-band / tap to select, drag to move, delete --------------------------

    /** A live selection: the page it lives on and the position-addressed elements it holds. */
    private data class ActiveSelection(val pageIndex: Int, val refs: Set<ElementRef>)

    /**
     * Down in Select mode. With a live selection, a touch near a corner **resizes**, near the
     * right-edge rotate knob (strokes only) **rotates**, and inside the outline **moves**; otherwise it starts
     * a new rubber-band (or lasso). The handle hit-tests run in view px against the drawn outline.
     */
    private fun beginSelect(event: MotionEvent) {
        scrolling = false
        erasing = false
        placing = false
        current = null
        val sel = selection
        if (sel != null && dispatchHandle(event, sel)) return
        beginBand(event)
    }

    /** Try to start a resize/rotate/move for [sel] from a down at (event.x, event.y); else false. */
    private fun dispatchHandle(event: MotionEvent, sel: ActiveSelection): Boolean {
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return false
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return false
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return false
        // Rotate knob poking out midway from the right edge, offered only when every element is a stroke.
        if (isAllStrokes(sel)) {
            val midY = ((b.top + b.bottom) / 2 * box.scale + box.topPx - scrollY).toFloat()
            val rightX = (b.right * box.scale + box.leftPx - scrollX).toFloat() + SELECT_PAD_PX + ROTATE_ARM_PX
            if (hypot(event.x - rightX, event.y - midY) <= HANDLE_HIT_PX) {
                beginRotate(event, box, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
                return true
            }
        }
        // Four corner resize handles; the anchor is the diagonally-opposite corner.
        val cornersPt = arrayOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)
        for (i in 0..3) {
            val padX = if (cornersPt[i].first == b.left) -SELECT_PAD_PX else SELECT_PAD_PX
            val padY = if (cornersPt[i].second == b.top) -SELECT_PAD_PX else SELECT_PAD_PX
            val hx = (cornersPt[i].first * box.scale + box.leftPx - scrollX).toFloat() + padX
            val hy = (cornersPt[i].second * box.scale + box.topPx - scrollY).toFloat() + padY
            if (hypot(event.x - hx, event.y - hy) <= HANDLE_HIT_PX) {
                val anchor = cornersPt[(i + 2) % 4]
                beginResize(event, box, anchor.first, anchor.second)
                return true
            }
        }
        // Inside the outline: move.
        if (b.expand(MOVE_GRAB_PAD).contains(ptX(box, event.x), ptY(box, event.y))) {
            beginMove(event, sel, box)
            return true
        }
        return false
    }

    /** True if every element in [sel] is a stroke (the only element whose rotation round-trips). */
    private fun isAllStrokes(sel: ActiveSelection): Boolean {
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return false
        return sel.refs.isNotEmpty() && sel.refs.all {
            page.layers.getOrNull(it.layerIndex)?.elements?.getOrNull(it.elementIndex) is Stroke
        }
    }

    private fun beginMove(event: MotionEvent, sel: ActiveSelection, box: PageBox) {
        movingSel = true
        gestureStartDoc = doc
        moveStartDoc = doc
        moveStartPtX = ptX(box, event.x)
        moveStartPtY = ptY(box, event.y)
    }

    private fun beginResize(event: MotionEvent, box: PageBox, anchorX: Double, anchorY: Double) {
        resizing = true
        gestureStartDoc = doc
        moveStartDoc = doc
        resizeAnchorX = anchorX
        resizeAnchorY = anchorY
        resizeStartDist = hypot(ptX(box, event.x) - anchorX, ptY(box, event.y) - anchorY).coerceAtLeast(1e-3)
    }

    /** Resize the selection: a uniform scale by (current distance / start distance) about the anchor. */
    private fun resizeSelect(event: MotionEvent) {
        val sel = selection ?: return
        val start = moveStartDoc ?: return
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val dist = hypot(ptX(box, event.x) - resizeAnchorX, ptY(box, event.y) - resizeAnchorY)
        val factor = (dist / resizeStartDist).coerceIn(MIN_RESIZE, MAX_RESIZE)
        doc = doc.copy(pages = SelectionOps.scale(start.pages, sel.pageIndex, sel.refs, factor, resizeAnchorX, resizeAnchorY))
        relayout()
        render()
    }

    private fun beginRotate(event: MotionEvent, box: PageBox, pivotX: Double, pivotY: Double) {
        rotating = true
        gestureStartDoc = doc
        moveStartDoc = doc
        rotatePivotX = pivotX
        rotatePivotY = pivotY
        rotateStartAngle = atan2(ptY(box, event.y) - pivotY, ptX(box, event.x) - pivotX)
    }

    /** Rotate the selection's strokes by the angle swept around the pivot since the gesture start. */
    private fun rotateSelect(event: MotionEvent) {
        val sel = selection ?: return
        val start = moveStartDoc ?: return
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val swept = atan2(ptY(box, event.y) - rotatePivotY, ptX(box, event.x) - rotatePivotX) - rotateStartAngle
        // Snapping the swept angle (not the absolute one) keeps the steps relative to where the
        // selection started, so a snapped drag returns exactly to the original orientation at rest.
        val angle = if (snapRotation) Snapping.snapAngle(swept) else swept
        doc = doc.copy(pages = SelectionOps.rotate(start.pages, sel.pageIndex, sel.refs, angle, rotatePivotX, rotatePivotY))
        relayout()
        render()
    }

    /**
     * Finish a move: if the selection's centre now sits over a different page, re-home the elements
     * onto that page's top layer (mapping through both pages' pt frames so they land where they're
     * dropped). The whole move records as one undoable edit via [finishGesture].
     */
    private fun commitMove() {
        val sel = selection ?: return
        val srcBox = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return
        val centreXContent = ((b.left + b.right) / 2 * srcBox.scale + srcBox.leftPx).toFloat()
        val centreYContent = ((b.top + b.bottom) / 2 * srcBox.scale + srcBox.topPx).toFloat()
        val target = layout.nearestPage(centreXContent, centreYContent) ?: return
        if (target.index == sel.pageIndex) return
        val s = srcBox.scale.toDouble() / target.scale.toDouble()
        val dx = (srcBox.leftPx - target.leftPx) / target.scale.toDouble()
        val dy = (srcBox.topPx - target.topPx) / target.scale.toDouble()
        val (pages, refs) = SelectionOps.moveToPage(doc.pages, sel.pageIndex, target.index, sel.refs, s, dx, dy)
        doc = doc.copy(pages = pages)
        selection = ActiveSelection(target.index, refs)
    }

    /** Drag the selection: translate the elements from their gesture-start positions, live. */
    private fun moveSelect(event: MotionEvent) {
        val sel = selection ?: return
        val start = moveStartDoc ?: return
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val dx = ptX(box, event.x) - moveStartPtX
        val dy = ptY(box, event.y) - moveStartPtY
        doc = doc.copy(pages = SelectionOps.translate(start.pages, sel.pageIndex, sel.refs, dx, dy))
        relayout()
        render()
    }

    /**
     * Down with the vertical-space tool: latch the grabbed page and the page-local Y of the grab
     * line. Everything whose top edge is below that line follows the drag ([VerticalSpaceOps]).
     */
    private fun beginVerticalSpace(event: MotionEvent) {
        scrolling = false; erasing = false; placing = false; current = null
        val box = layout.pageAt(event.x + scrollX, event.y + scrollY) ?: return
        vspacing = true
        vspacePage = box.index
        currentPage = box.index
        vspaceLinePt = ptY(box, event.y)
        vspaceLineViewY = event.y
        gestureStartDoc = doc
        moveStartDoc = doc
        gesturePointerId = event.getPointerId(0)
        render()
    }

    /** Drag the vertical-space line: re-apply the whole shift from the gesture-start snapshot. */
    private fun verticalSpaceMove(event: MotionEvent) {
        val start = moveStartDoc ?: return
        val box = layout.boxes.getOrNull(vspacePage) ?: return
        val dy = ptY(box, event.y) - vspaceLinePt
        doc = doc.copy(pages = VerticalSpaceOps.shiftBelow(start.pages, vspacePage, vspaceLinePt, dy))
        relayout()
        render()
    }

    private fun beginBand(event: MotionEvent) {
        if (selection != null) {
            selection = null
            onSelectionChanged?.invoke(false)
        }
        banding = true
        bandPage = layout.pageAt(event.x + scrollX, event.y + scrollY)?.index ?: 0
        bandX0 = event.x; bandY0 = event.y
        bandX1 = event.x; bandY1 = event.y
        lassoPts.clear()
        if (lassoMode) { lassoPts.add(event.x); lassoPts.add(event.y) }
        render()
    }

    private fun bandMove(event: MotionEvent) {
        bandX1 = event.x
        bandY1 = event.y
        if (lassoMode) { lassoPts.add(event.x); lassoPts.add(event.y) }
        render()
    }

    /**
     * Up from a marquee: a tap (no real drag) picks the topmost element; a rectangle drag selects
     * everything wholly enclosed; a lasso drag selects everything wholly inside the traced polygon.
     */
    private fun commitBand() {
        val box = layout.boxes.getOrNull(bandPage) ?: return
        val page = doc.pages.getOrNull(bandPage) ?: return
        val isTap = hypot(bandX1 - bandX0, bandY1 - bandY0) <= TAP_SLOP_PX
        val refs: Set<ElementRef> = when {
            isTap -> SelectionTester.pickTopmost(page, ptX(box, bandX0), ptY(box, bandY0))?.let { setOf(it) } ?: emptySet()
            lassoMode -> {
                val poly = ArrayList<Vec2>(lassoPts.size / 2)
                var i = 0
                while (i < lassoPts.size) { poly.add(Vec2(ptX(box, lassoPts[i]), ptY(box, lassoPts[i + 1]))); i += 2 }
                SelectionTester.inPolygon(page, poly)
            }
            else -> {
                val rect = Bounds(
                    min(ptX(box, bandX0), ptX(box, bandX1)), min(ptY(box, bandY0), ptY(box, bandY1)),
                    max(ptX(box, bandX0), ptX(box, bandX1)), max(ptY(box, bandY0), ptY(box, bandY1)),
                )
                SelectionTester.inRect(page, rect)
            }
        }
        selection = if (refs.isEmpty()) null else ActiveSelection(bandPage, refs)
        onSelectionChanged?.invoke(selection != null)
        render()
    }

    // --- PDF text selection ---------------------------------------------------------------------

    /** Down with the text-select tool: anchor the selection at the word nearest the touch. */
    private fun beginTextSelect(event: MotionEvent) {
        scrolling = false; erasing = false; placing = false; current = null
        val index = pdfTextIndex ?: return
        val pageIndex = layout.pageAt(event.x + scrollX, event.y + scrollY)?.index ?: return
        val box = layout.boxes.getOrNull(pageIndex) ?: return
        val anchor = index.anchorWord(pageIndex, ptX(box, event.x), ptY(box, event.y)) ?: return
        textSelecting = true
        textSelPage = pageIndex
        textSelAnchor = anchor
        textSelFocus = anchor
        onTextSelectionChanged?.invoke(true)
        render()
    }

    /** Drag: extend the selection to the word nearest the touch (kept on the anchor's page). */
    private fun textSelectMove(event: MotionEvent) {
        val index = pdfTextIndex ?: return
        val box = layout.boxes.getOrNull(textSelPage) ?: return
        val focus = index.anchorWord(textSelPage, ptX(box, event.x), ptY(box, event.y)) ?: return
        if (focus != textSelFocus) { textSelFocus = focus; render() }
    }

    /** True while a PDF-text selection is active (drives the Copy affordance). */
    fun hasTextSelection(): Boolean = textSelPage >= 0 && textSelAnchor >= 0

    /** Copy the selected PDF text to the Android system clipboard. */
    fun copyTextSelection() {
        val index = pdfTextIndex ?: return
        if (!hasTextSelection()) return
        val text = index.rangeText(textSelPage, textSelAnchor, textSelFocus)
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
    }

    /** Drop the current PDF-text selection (view-only). */
    fun clearTextSelection() {
        val had = textSelPage >= 0
        textSelecting = false
        textSelPage = -1
        textSelAnchor = -1
        textSelFocus = -1
        if (had) {
            onTextSelectionChanged?.invoke(false)
            render()
        }
    }

    /** Delete every selected element as one undoable edit. */
    fun deleteSelection() {
        val sel = selection ?: return
        val before = doc
        doc = doc.copy(pages = SelectionOps.delete(doc.pages, sel.pageIndex, sel.refs))
        selection = null
        onSelectionChanged?.invoke(false)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** Drop the current selection (a view-only change; not recorded in history). */
    fun clearSelection() {
        if (selection == null) return
        selection = null
        onSelectionChanged?.invoke(false)
        render()
    }

    /** Recolour and/or re-width the selected elements as one undoable edit (selection stays). */
    fun restyleSelection(color: Int?, widthPt: Double?) {
        val sel = selection ?: return
        val before = doc
        val pages = SelectionOps.restyle(doc.pages, sel.pageIndex, sel.refs, color, widthPt)
        if (pages === doc.pages) return
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** Copy the selected elements to the clipboard (leaves the document and selection unchanged). */
    fun copySelection() {
        val sel = selection ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        clipboard = SelectionOps.elementsAt(page, sel.refs)
        onClipboardChanged?.invoke(clipboard.isNotEmpty())
    }

    /** Copy then delete the selection (one undoable edit via [deleteSelection]). */
    fun cutSelection() {
        if (selection == null) return
        copySelection()
        deleteSelection()
    }

    /** Whether the clipboard currently holds anything to paste. */
    fun hasClipboard(): Boolean = clipboard.isNotEmpty()

    /** Paste the clipboard onto the visible page (offset a little), selecting the pasted copies. */
    fun pasteClipboard() {
        if (clipboard.isEmpty()) return
        val target = visiblePageIndex()
        pasteOnto(target, clipboard.map { SelectionOps.translate(it, PASTE_OFFSET_PT, PASTE_OFFSET_PT) })
    }

    /** Duplicate the selection in place (offset a little), selecting the duplicates. */
    fun duplicateSelection() {
        val sel = selection ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        val copies = SelectionOps.elementsAt(page, sel.refs).map { SelectionOps.translate(it, PASTE_OFFSET_PT, PASTE_OFFSET_PT) }
        pasteOnto(sel.pageIndex, copies)
    }

    /** Append [elements] to [pageIndex]'s top layer as one undoable edit and select them. */
    private fun pasteOnto(pageIndex: Int, elements: List<Element>) {
        if (elements.isEmpty()) return
        val before = doc
        val (pages, refs) = SelectionOps.addToTopLayer(doc.pages, pageIndex, elements)
        if (refs.isEmpty()) return
        doc = doc.copy(pages = pages)
        selection = ActiveSelection(pageIndex, refs)
        onSelectionChanged?.invoke(true)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /**
     * The page nearest the viewport centre — the target for a paste, and what a tab records so
     * re-selecting it lands back where you left off (see `com.xopp.android.tabs`).
     */
    fun visiblePageIndex(): Int =
        layout.nearestPage(scrollX + width / 2f, scrollY + height / 2f)?.index ?: currentPage

    /** View px -> page-local pt for [box]. */
    private fun ptX(box: PageBox, vx: Float): Double = ((vx + scrollX - box.leftPx) / box.scale).toDouble()
    private fun ptY(box: PageBox, vy: Float): Double = ((vy + scrollY - box.topPx) / box.scale).toDouble()

    /** [x] pt pulled onto [box]'s background ruling when the snap-to-grid setting is on. */
    private fun snapX(box: PageBox, x: Double): Double =
        if (snapToGrid) Snapping.snap(x, Snapping.spacingX(box.page.background)) else x

    /** [y] pt pulled onto [box]'s background ruling when the snap-to-grid setting is on. */
    private fun snapY(box: PageBox, y: Double): Double =
        if (snapToGrid) Snapping.snap(y, Snapping.spacingY(box.page.background)) else y

    // --- the setsquare / compass guide: place it, drag it, and rule drawn points against it -------

    /**
     * Put a fresh guide of [kind] at the middle of the visible page (or clear it for
     * [GuideKind.NONE]). Placing at the viewport centre rather than the page centre is what makes
     * the guide land under the user's hand when they turn it on while zoomed in.
     */
    fun placeGuide(kind: GuideKind) {
        val index = visiblePageIndex()
        val box = layout.boxes.getOrNull(index)
        // Asked for before the first layout (a guide restored from settings): hold it until
        // surfaceChanged, so it lands under the user's eye instead of at the page's own middle.
        if (kind != GuideKind.NONE && (box == null || width <= 0 || height <= 0)) {
            pendingGuide = kind
        }
        // Before the first layout there is no viewport to centre on, so fall back to the page's own
        // middle — that way a guide restored at launch still lands somewhere sensible.
        val laidOut = box != null && width > 0 && height > 0
        val cx = when {
            laidOut -> ptX(box!!, width / 2f)
            box != null -> box.page.width / 2
            else -> 0.0
        }
        val cy = when {
            laidOut -> ptY(box!!, height / 2f)
            box != null -> box.page.height / 2
            else -> 0.0
        }
        guidePage = index
        guide = kind.place(cx, cy)
        onGuideChanged?.invoke(guide)
        render()
    }

    /** Restore a remembered [pose] onto [page] without disturbing the viewport (used on load). */
    fun restoreGuide(pose: DrawingGuide?, page: Int = 0) {
        guide = pose
        guidePage = page
        render()
    }

    /**
     * ([x], [y]) page pt pulled onto the guide's nearest edge when one is placed on [box]'s page and
     * the point is within reach. Every drawn vertex — freehand and shape-tool alike — goes through
     * here, which is what makes the guide behave like a physical straightedge held against the page.
     */
    private fun guided(box: PageBox, x: Double, y: Double): Pair<Double, Double> {
        val g = guide ?: return x to y
        if (box.index != guidePage) return x to y
        return g.project(x, y)
    }

    /**
     * Start dragging the guide if this finger landed on it: on the tip handle it re-poses the guide
     * (rotate + resize the setsquare, open the compass), anywhere else on its body it slides it.
     * Returns false when the touch missed, so the gesture falls through to the ordinary pan/draw.
     */
    private fun beginGuideDrag(event: MotionEvent, pointerIndex: Int): Boolean {
        val g = guide ?: return false
        val box = layout.boxes.getOrNull(guidePage) ?: return false
        val px = ptX(box, event.getX(pointerIndex))
        val py = ptY(box, event.getY(pointerIndex))
        val handleReach = (HANDLE_HIT_PX / box.scale.coerceAtLeast(0.01f)).toDouble()
        val tip = guideTip(g)
        if (hypot(px - tip.first, py - tip.second) <= handleReach) {
            guideDrag = GUIDE_DRAG_TIP
        } else if (guideBodyHit(g, px, py, handleReach)) {
            guideDrag = GUIDE_DRAG_BODY
            guideGrabDx = px - g.x
            guideGrabDy = py - g.y
        } else {
            return false
        }
        guidePointerId = event.getPointerId(pointerIndex)
        return true
    }

    /** The re-pose handle: the setsquare's long-leg tip, or the compass's pencil point. */
    private fun guideTip(g: DrawingGuide): Pair<Double, Double> = when (g) {
        is DrawingGuide.Setsquare -> g.corners()[1]
        is DrawingGuide.Compass -> (g.x + g.radius) to g.y
    }

    /**
     * True when ([px], [py]) is on the guide's grabbable body: the setsquare's *interior* or the
     * compass's hub. Deliberately **not** the drawing edge — the edge belongs to the pen, so that a
     * finger drawn along it still draws instead of dragging the instrument out from under itself.
     */
    private fun guideBodyHit(g: DrawingGuide, px: Double, py: Double, hubReach: Double): Boolean =
        when (g) {
            is DrawingGuide.Setsquare -> g.contains(px, py)
            is DrawingGuide.Compass -> hypot(px - g.x, py - g.y) <= hubReach
        }

    private fun guideMove(event: MotionEvent) {
        val g = guide ?: return
        val box = layout.boxes.getOrNull(guidePage) ?: return
        val pointerIndex = event.findPointerIndex(guidePointerId)
        if (pointerIndex < 0) return
        val px = ptX(box, event.getX(pointerIndex))
        val py = ptY(box, event.getY(pointerIndex))
        guide = when {
            guideDrag == GUIDE_DRAG_BODY -> g.moved(px - guideGrabDx - g.x, py - guideGrabDy - g.y)
            g is DrawingGuide.Setsquare -> g.aimedAt(px, py, snapRotation)
            g is DrawingGuide.Compass -> g.openedTo(px, py)
            else -> g
        }
        render()
    }

    /** Release the guide when the finger holding it lifts; other pointers lifting leave it held. */
    private fun endGuideDrag(event: MotionEvent?) {
        if (guideDrag == GUIDE_DRAG_NONE) return
        if (event != null && event.getPointerId(event.actionIndex) != guidePointerId) return
        guideDrag = GUIDE_DRAG_NONE
        guidePointerId = -1
        onGuideChanged?.invoke(guide)
    }

    /** Draw the guide overlay in view px: the setsquare's outline, or the compass's circle and hub. */
    private fun drawGuide(canvas: Canvas) {
        val g = guide ?: return
        val box = layout.boxes.getOrNull(guidePage) ?: return
        val s = box.scale
        val ox = box.leftPx - scrollX
        val oy = box.topPx - scrollY
        fun vx(x: Double) = (x * s + ox).toFloat()
        fun vy(y: Double) = (y * s + oy).toFloat()
        when (g) {
            is DrawingGuide.Setsquare -> {
                val c = g.corners()
                guidePath.reset()
                guidePath.moveTo(vx(c[0].first), vy(c[0].second))
                guidePath.lineTo(vx(c[1].first), vy(c[1].second))
                guidePath.lineTo(vx(c[2].first), vy(c[2].second))
                guidePath.close()
                canvas.drawPath(guidePath, guideFillPaint)
                canvas.drawPath(guidePath, guideStrokePaint)
            }
            is DrawingGuide.Compass -> {
                canvas.drawCircle(vx(g.x), vy(g.y), (g.radius * s).toFloat(), guideStrokePaint)
                canvas.drawCircle(vx(g.x), vy(g.y), HANDLE_DRAW_PX, guideHandlePaint)
            }
        }
        val tip = guideTip(g)
        canvas.drawCircle(vx(tip.first), vy(tip.second), HANDLE_DRAW_PX, guideHandlePaint)
    }

    // --- touch: the pen draws (or erases), fingers pan; input is routed through InputClassifier ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling(); beginPointer(event, 0); beginHandTap(event); armPageDrag(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { handTapCandidate = false; cancelPageDrag(); onPointerDown(event) }
            MotionEvent.ACTION_MOVE -> {
                if (handTapCandidate) trackHandTapMove(event)
                trackPageDragArm(event)
                // A lifted page owns the gesture outright — no panning, drawing or erasing underneath it.
                if (pageDragIndex >= 0) { pageDragMove(event); return true }
                // The guide is dragged by its own finger and runs *alongside* the other gestures —
                // holding it steady while the pen rules along it is the whole point.
                if (guideDrag != GUIDE_DRAG_NONE) guideMove(event)
                when {
                    scrolling -> doScroll(event)
                    erasing -> eraseMove(event)
                    placing -> placeMove(event)
                    resizing -> resizeSelect(event)
                    rotating -> rotateSelect(event)
                    movingSel -> moveSelect(event)
                    vspacing -> verticalSpaceMove(event)
                    banding -> bandMove(event)
                    textSelecting -> textSelectMove(event)
                    splineDragging -> splineMove(event)
                    current != null -> extendStroke(event)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            // A spline node is still mid-curve on release — it must not run the commit-and-finish path.
            MotionEvent.ACTION_UP -> when {
                pageDragIndex >= 0 -> { pageDragArmed = false; removeCallbacks(pageDragArm); finishPageDrag() }
                splineDragging -> splineUp(event)
                else -> { cancelPageDrag(); captureReleaseVelocity(event); handleHandTapUp(event); endGesture() }
            }
            MotionEvent.ACTION_CANCEL -> { handTapCandidate = false; cancelPageDrag(); cancelGesture() }
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /** Read the pan's release velocity and adopt it as the fling seed, but only when the release was a
     * real flick (>= [Fling.MIN_SPEED_PX]); a slow drift, or the near-motionless single-finger tail of
     * a two-finger release, stays at the zero seeded by [beginScroll], so only a genuine one-finger
     * flick coasts. The magnitude→coast response is shaped later by [Momentum.seed]. */
    private fun captureReleaseVelocity(event: MotionEvent) {
        velocityEstimator.add(event.eventTime, focusX(event, skip = -1), focusY(event, skip = -1))
        val v = velocityEstimator.velocity()
        if (hypot(v.vx, v.vy) >= Fling.MIN_SPEED_PX) setReleaseVelocity(v)
    }

    /** Latch [v] as the release velocity: content glides opposite the finger, clamped to the platform's
     * max fling speed. */
    private fun setReleaseVelocity(v: Velocity) {
        releaseVx = (-v.vx).coerceIn(-maxFlingVelocity, maxFlingVelocity)
        releaseVy = (-v.vy).coerceIn(-maxFlingVelocity, maxFlingVelocity)
    }

    // --- page-overview drag-to-reorder -----------------------------------------------------------
    // Only in the multi-column grid, and only for a finger: a long-press lifts the page under it, the
    // drag tracks a drop slot, and the release commits one undoable [PageOps.move]. The pen is never a
    // candidate, so drawing on a grid page is untouched.

    /** Arm the long-press that lifts a page, for a single finger down on the overview grid. */
    private fun armPageDrag(event: MotionEvent) {
        cancelPageDrag()
        if (columns <= 1 || !pagesEditMode || event.pointerCount != 1) return
        if (pointerKindOf(event, 0) != PointerKind.FINGER) return
        pageDragArmed = true
        pageDragDownX = event.x
        pageDragDownY = event.y
        postDelayed(pageDragArm, longPressMs)
    }

    /** A touch that travels past slop before the timeout is a pan, not a page lift. */
    private fun trackPageDragArm(event: MotionEvent) {
        if (!pageDragArmed) return
        if (hypot(event.x - pageDragDownX, event.y - pageDragDownY) > touchSlopPx) cancelPageDrag()
    }

    /** The long-press fired: lift the page under the finger and take the gesture away from panning. */
    private fun startPageDrag() {
        pageDragArmed = false
        if (columns <= 1 || doc.pages.size < 2) return
        val box = layout.pageAt(scrollX + pageDragDownX, scrollY + pageDragDownY) ?: return
        stopFling()
        scrolling = false
        pageDragIndex = box.index
        pageDropIndex = box.index
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        render()
    }

    /** Track the finger to the page it is hovering over — that slot is where the drop lands. */
    private fun pageDragMove(event: MotionEvent) {
        val target = layout.nearestPage(scrollX + event.x, scrollY + event.y) ?: return
        if (target.index == pageDropIndex) return
        pageDropIndex = target.index
        render()
    }

    /** Commit the drop as one undoable reorder and follow the page to its new home. */
    private fun finishPageDrag() {
        val from = pageDragIndex
        val to = pageDropIndex
        pageDragIndex = -1
        pageDropIndex = -1
        if (from < 0 || to < 0 || to == from) { render(); return }
        editPages(PageOps.move(doc.pages, from, to))
        goToPage(to)
    }

    /** Disarm the pending long-press and drop any in-flight lift without reordering. */
    private fun cancelPageDrag() {
        if (pageDragArmed) removeCallbacks(pageDragArm)
        pageDragArmed = false
        val wasDragging = pageDragIndex >= 0
        pageDragIndex = -1
        pageDropIndex = -1
        if (wasDragging) render()
    }

    /** Grey out the lifted page and outline the slot it would drop into. */
    private fun drawPageDrag(canvas: Canvas) {
        layout.boxes.getOrNull(pageDragIndex)?.let { box ->
            canvas.drawRect(
                box.leftPx - scrollX, box.topPx - scrollY,
                box.rightPx - scrollX, box.bottomPx - scrollY, pageLiftPaint,
            )
        }
        layout.boxes.getOrNull(pageDropIndex)?.let { box ->
            canvas.drawRect(
                box.leftPx - scrollX, box.topPx - scrollY,
                box.rightPx - scrollX, box.bottomPx - scrollY, pageDropPaint,
            )
        }
    }

    /** Tint and outline every selected page, so the pending bulk delete's targets are obvious. */
    private fun drawPageSelection(canvas: Canvas) {
        for (index in selectedPages) {
            val box = layout.boxes.getOrNull(index) ?: continue
            val l = box.leftPx - scrollX
            val t = box.topPx - scrollY
            val r = box.rightPx - scrollX
            val b = box.bottomPx - scrollY
            canvas.drawRect(l, t, r, b, pageSelectFillPaint)
            canvas.drawRect(l, t, r, b, pageSelectPaint)
        }
    }

    /** Arm double-tap tracking for a fresh single-finger touch, but only while the Hand tool is active. */
    private fun beginHandTap(event: MotionEvent) {
        handTapCandidate = handMode
        handTapMoved = false
        handTapDownTime = event.eventTime
        handTapDownX = event.x
        handTapDownY = event.y
    }

    /** A moved-too-far touch is a pan, not a tap — disqualify it from forming a double-tap. */
    private fun trackHandTapMove(event: MotionEvent) {
        if (hypot(event.x - handTapDownX, event.y - handTapDownY) > doubleTapSlopPx) handTapMoved = true
    }

    /** On lift, confirm a tap and — if it pairs with the previous one in time and place — fire the double-tap. */
    private fun handleHandTapUp(event: MotionEvent) {
        if (!handTapCandidate) return
        handTapCandidate = false
        // A flick is a pan, not a tap — even one whose travel stayed inside the tap slop. Without this
        // a short-but-fast swipe in the overview grid would count as a tap and jump back to the page
        // under the finger, cancelling the glide it should have started.
        if (handTapMoved || releaseVx != 0f || releaseVy != 0f) { handFirstTapTime = 0L; return }
        // In the overview grid a tap is about pages, not paging/zooming around: in edit mode it picks
        // pages out for a bulk edit, in view mode it just jumps to the page you tapped.
        if (columns > 1) {
            handFirstTapTime = 0L
            val box = layout.pageAt(scrollX + handTapDownX, scrollY + handTapDownY) ?: return
            if (pagesEditMode) togglePageSelection(box.index) else goToPage(box.index)
            return
        }
        val pairsWithPrevious = handFirstTapTime != 0L &&
            handTapDownTime - handFirstTapTime <= doubleTapTimeoutMs &&
            hypot(handTapDownX - handFirstTapX, handTapDownY - handFirstTapY) <= doubleTapSlopPx
        if (pairsWithPrevious) {
            handFirstTapTime = 0L // consume, so a third tap doesn't immediately re-fire
            onHandDoubleTap(handTapDownX)
        } else {
            handFirstTapTime = handTapDownTime
            handFirstTapX = handTapDownX
            handFirstTapY = handTapDownY
        }
    }

    /** Route a Hand-tool double-tap by horizontal zone: left third → prev page, right third → next, centre → toggle full-page. */
    private fun onHandDoubleTap(x: Float) {
        val edge = width / 3f
        when {
            x < edge -> goToPage(currentPageIndex() - 1)
            x > width - edge -> goToPage(currentPageIndex() + 1)
            else -> onToggleFullPage?.invoke()
        }
    }

    /** A hovering stylus (tip not yet down) drives a preview dot so the user can see where it'll land. */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val kind = pointerKindOf(event, 0)
        if (!showHover || (kind != PointerKind.STYLUS && kind != PointerKind.ERASER_TIP)) {
            if (hovering) { hovering = false; render() }
            return super.onHoverEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true; hoverX = event.x; hoverY = event.y; render()
            }
            MotionEvent.ACTION_HOVER_EXIT -> { hovering = false; render() }
        }
        return true
    }

    /** Map one event pointer's tool type to our device-independent [PointerKind]. */
    private fun pointerKindOf(event: MotionEvent, pointerIndex: Int): PointerKind =
        when (event.getToolType(pointerIndex)) {
            MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
            MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
            MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
            else -> PointerKind.UNKNOWN
        }

    private fun barrelPressed(event: MotionEvent): Boolean =
        (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    /** The on-screen tool collapsed to the classifier's [ActiveTool]. */
    private fun activeTool(): ActiveTool = when {
        verticalSpaceMode -> ActiveTool.VERTICAL_SPACE
        placeKind != null -> ActiveTool.PLACE
        handMode -> ActiveTool.HAND
        textSelectMode -> ActiveTool.TEXT_SELECT
        selectMode -> ActiveTool.SELECT
        tool == Tool.ERASER -> ActiveTool.ERASER
        tool == Tool.HIGHLIGHTER -> ActiveTool.HIGHLIGHTER
        else -> ActiveTool.PEN
    }

    /** Begin a gesture for the pointer at [pointerIndex], its intent decided by [InputClassifier]. */
    private fun beginPointer(event: MotionEvent, pointerIndex: Int) {
        val kind = pointerKindOf(event, pointerIndex)
        // A finger laid on the guide manipulates it, whatever the active tool — the pen keeps
        // drawing against it meanwhile, exactly as you'd hold a real setsquare down and rule along it.
        if (kind == PointerKind.FINGER && beginGuideDrag(event, pointerIndex)) return
        // Play-object is a pure query — it never edits the document, so it short-circuits the whole
        // gesture machinery rather than earning a GestureIntent of its own.
        if (audioPlayMode) { audioTap(event, pointerIndex); return }
        val intent = InputClassifier.classify(kind, barrelPressed(event), activeTool(), inputSettings)
        stylusOwner = (kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP) &&
            (intent == GestureIntent.DRAW || intent == GestureIntent.ERASE)
        when (intent) {
            GestureIntent.PLACE -> beginPlace(event, pointerIndex)
            GestureIntent.PAN -> beginScroll(event)
            GestureIntent.SELECT -> beginSelect(event)
            GestureIntent.SELECT_TEXT -> beginTextSelect(event)
            GestureIntent.VERTICAL_SPACE -> beginVerticalSpace(event)
            GestureIntent.ERASE -> startErase(event, pointerIndex)
            // The spline tool is laid down over many taps, so it gets its own gesture path.
            GestureIntent.DRAW ->
                if (shapeKind == ShapeKind.SPLINE) splineDown(event, pointerIndex)
                else startStroke(event, pointerIndex)
            GestureIntent.IGNORE -> Unit
        }
    }

    /**
     * A second pointer arrived. A stylus/eraser tip takes over any in-progress finger gesture (so a
     * palm that landed first can't block the pen); while a stylus already owns the gesture, extra
     * finger/palm pointers are ignored (palm rejection); otherwise two fingers pan.
     */
    private fun onPointerDown(event: MotionEvent) {
        val idx = event.actionIndex
        val kind = pointerKindOf(event, idx)
        when {
            kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP -> {
                abandonInProgress()
                beginPointer(event, idx)
            }
            stylusOwner -> Unit // palm / finger resting while the pen writes: ignore
            else -> beginScroll(event) // ordinary two-finger pan
        }
    }

    /** If the pointer that lifted owns the draw/erase gesture, finish it; otherwise keep panning. */
    private fun onPointerUp(event: MotionEvent) {
        endGuideDrag(event)
        val upId = event.getPointerId(event.actionIndex)
        if (upId == gesturePointerId && (current != null || erasing)) {
            endGesture()
            return
        }
        lastFocusY = focusY(event, skip = event.actionIndex)
        lastFocusX = focusX(event, skip = event.actionIndex)
        // The span also jumps when a finger leaves; re-baseline it next frame so the drop isn't read as
        // a pinch-out, mirroring the focus/velocity reset below.
        lastSpan = 0f
        // The focus jumps when a finger leaves, so restart the estimate from the new baseline —
        // otherwise that discontinuity would read as a huge phantom flick. A side effect is that a
        // two-finger release carries no momentum (its near-motionless single-finger tail is all that
        // survives the reset), which is the intended feel — only a one-finger pan flings.
        velocityEstimator.reset()
        velocityEstimator.add(event.eventTime, lastFocusX, lastFocusY)
    }

    /** Drop any in-progress draw/erase/place/band/move without committing (a stylus is taking over). */
    private fun abandonInProgress() {
        clearSpline()
        current = null; shaping = false; erasing = false; placing = false; banding = false
        movingSel = false; resizing = false; rotating = false; scrolling = false; textSelecting = false
        vspacing = false
    }

    private fun cancelGesture() {
        stopFling()
        endGuideDrag(null)
        clearSpline()
        current = null; shaping = false; scrolling = false; erasing = false; placing = false
        movingSel = false; resizing = false; rotating = false; banding = false; gestureStartDoc = null
        textSelecting = false; vspacing = false
        gesturePointerId = -1; stylusOwner = false
    }

    private fun startStroke(event: MotionEvent, pointerIndex: Int) {
        scrolling = false
        shaping = shapeKind != null
        gestureStartDoc = doc
        gesturePointerId = event.getPointerId(pointerIndex)
        val box = layout.pageAt(event.getX(pointerIndex) + scrollX, event.getY(pointerIndex) + scrollY)
            ?: run { current = null; return }
        currentPage = box.index
        if (shaping) {
            // Grid snap first, then the guide — a placed guide is the stronger constraint and must
            // not be undone by pulling the point back onto the ruling.
            val (sx, sy) = guided(
                box,
                snapX(box, ptX(box, event.getX(pointerIndex))),
                snapY(box, ptY(box, event.getY(pointerIndex))),
            )
            shapeStartX = sx
            shapeStartY = sy
            current = ArrayList(listOf(StrokePoint(shapeStartX, shapeStartY, baseWidthPt.toDouble())))
        } else {
            // Decimate against this page's real px/pt, so a stroke drawn zoomed out or in a
            // multi-column view keeps the same document-space detail as one drawn at 100%.
            smoother.reset(strokePrecision.stepPxFor(box.scale))
            current = ArrayList<StrokePoint>().also { addSamples(event, pointerIndex, box, it) }
        }
    }

    private fun extendStroke(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(gesturePointerId)
        if (pointerIndex < 0) return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        if (shaping) {
            val (ex, ey) = guided(
                box,
                snapX(box, ptX(box, event.getX(pointerIndex))),
                snapY(box, ptY(box, event.getY(pointerIndex))),
            )
            current = ArrayList(
                ShapeBuilder.build(shapeKind ?: return, shapeStartX, shapeStartY, ex, ey, baseWidthPt.toDouble()),
            )
            render()
        } else {
            current?.let { addSamples(event, pointerIndex, box, it); render() }
        }
    }

    // --- the spline tool: tap to add a control point, drag to curve it, double-tap to finish -------

    /**
     * A touch while the spline tool is active. A tap that pairs with the previous one (double-tap)
     * closes the curve; otherwise it appends a control point, which the following drag can curve.
     */
    private fun splineDown(event: MotionEvent, pointerIndex: Int) {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (splineNodes.isNotEmpty() && pairsWithPreviousSplineTap(event.eventTime, x, y)) {
            finishSpline()
            return
        }
        scrolling = false
        // The first node fixes the page for the whole curve, so later taps stay in one stroke.
        val box = if (splineNodes.isEmpty()) layout.pageAt(x + scrollX, y + scrollY) else layout.boxes.getOrNull(currentPage)
        if (box == null) return
        if (splineNodes.isEmpty()) {
            currentPage = box.index
            gestureStartDoc = doc
        }
        gesturePointerId = event.getPointerId(pointerIndex)
        splineAnchorX = ptX(box, x)
        splineAnchorY = ptY(box, y)
        splineNodes += SplineNode(splineAnchorX, splineAnchorY)
        splineDragging = true
        splineTapTime = event.eventTime
        splineTapX = x
        splineTapY = y
        renderSplinePreview()
    }

    /** Dragging away from the tap grows the newest node's tangent handle, curving the curve live. */
    private fun splineMove(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(gesturePointerId)
        if (pointerIndex < 0) return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        val tx = ptX(box, event.getX(pointerIndex)) - splineAnchorX
        val ty = ptY(box, event.getY(pointerIndex)) - splineAnchorY
        splineNodes[splineNodes.lastIndex] = SplineNode(splineAnchorX, splineAnchorY, tx, ty)
        renderSplinePreview()
    }

    /** The node is placed; the curve stays open, waiting for the next tap (or the finishing double-tap). */
    private fun splineUp(event: MotionEvent) {
        splineDragging = false
        gesturePointerId = -1
        stylusOwner = false
        // A drag isn't a tap, so it can't be half of the double-tap that finishes the curve.
        if (hypot(event.x - splineTapX, event.y - splineTapY) > doubleTapSlopPx) splineTapTime = 0L
        renderSplinePreview()
    }

    private fun pairsWithPreviousSplineTap(time: Long, x: Float, y: Float): Boolean =
        splineTapTime != 0L && time - splineTapTime <= doubleTapTimeoutMs &&
            hypot(x - splineTapX, y - splineTapY) <= doubleTapSlopPx

    /** Show the curve-so-far as the in-progress stroke, so it paints exactly as it will commit. */
    private fun renderSplinePreview() {
        current = ArrayList(SplineBuilder.build(splineNodes, baseWidthPt.toDouble()))
        render()
    }

    /** True while a spline is open — the editor uses this to decide whether Enter/Esc apply. */
    fun splineInProgress(): Boolean = splineNodes.isNotEmpty()

    /**
     * Commit the open spline as one ordinary stroke (the same shape any other tool produces) and
     * clear the tool's state. Safe to call when nothing is open; a one-node spline is just dropped.
     */
    fun finishSpline() {
        if (splineNodes.isEmpty()) return
        val pts = SplineBuilder.build(splineNodes, baseWidthPt.toDouble())
        clearSpline()
        if (pts.size >= 2) {
            appendStroke(
                currentPage,
                Stroke(
                    tool, strokeColor(), "round", pts, true,
                    lineStyle = currentLineStyle, fill = currentFill,
                ),
            )
        }
        finishGesture()
        render()
    }

    /** Throw the open spline away without committing it (Escape, or switching tools mid-curve). */
    fun cancelSpline() {
        if (splineNodes.isEmpty()) return
        clearSpline()
        gestureStartDoc = null
        render()
    }

    private fun clearSpline() {
        splineNodes.clear()
        splineDragging = false
        splineTapTime = 0L
        current = null
        gesturePointerId = -1
        stylusOwner = false
    }

    /** The eraser: touch/drag deletes any stroke it passes over on the page under the pointer. */
    private fun startErase(event: MotionEvent, pointerIndex: Int) {
        scrolling = false
        erasing = true
        gestureStartDoc = doc
        gesturePointerId = event.getPointerId(pointerIndex)
        val box = layout.pageAt(event.getX(pointerIndex) + scrollX, event.getY(pointerIndex) + scrollY) ?: return
        currentPage = box.index
        eraseAt(box, event.getX(pointerIndex), event.getY(pointerIndex))
    }

    private fun eraseMove(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(gesturePointerId)
        if (pointerIndex < 0) return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        eraseAt(box, event.getX(pointerIndex), event.getY(pointerIndex))
    }

    private fun eraseAt(box: PageBox, vx: Float, vy: Float) {
        val px = ((vx + scrollX - box.leftPx) / box.scale).toDouble()
        val py = ((vy + scrollY - box.topPx) / box.scale).toDouble()
        if (eraseOnPage(currentPage, px, py, eraserRadiusPt)) render()
    }

    /** A tap in a placement tool: remember where it went down; a small drag cancels it. */
    private fun beginPlace(event: MotionEvent, pointerIndex: Int) {
        scrolling = false
        erasing = false
        current = null
        placing = true
        placeDownX = event.getX(pointerIndex)
        placeDownY = event.getY(pointerIndex)
    }

    /** Moving past the tap slop turns a placement into a no-op (the user is scrubbing, not tapping). */
    private fun placeMove(event: MotionEvent) {
        if (hypot(event.x - placeDownX, event.y - placeDownY) > TAP_SLOP_PX) placing = false
    }

    /** Fire [onPlace] for the page/point the tap landed on, hitting an existing text box if any. */
    private fun commitPlace() {
        val kind = placeKind ?: return
        val box = layout.pageAt(placeDownX + scrollX, placeDownY + scrollY) ?: return
        val xPt = ((placeDownX + scrollX - box.leftPx) / box.scale).toDouble()
        val yPt = ((placeDownY + scrollY - box.topPx) / box.scale).toDouble()
        val existing = if (kind == PlaceKind.TEXT) pickText(box.index, xPt, yPt)?.also { editingTarget = it } else null
        onPlace?.invoke(kind, Placement(box.index, xPt, yPt, existing))
    }

    /** A second finger (or the Hand tool) started panning: abandon any partial stroke/erase/place. */
    private fun beginScroll(event: MotionEvent) {
        current = null
        erasing = false
        placing = false
        scrolling = true
        lastFocusY = focusY(event, skip = -1)
        lastFocusX = focusX(event, skip = -1)
        lastSpan = spanOf(event)
        // A fresh pan starts with no carried flick — otherwise a near-motionless release could keep a
        // latched velocity from the previous gesture (see [captureReleaseVelocity]).
        releaseVx = 0f
        releaseVy = 0f
        velocityEstimator.reset()
        velocityEstimator.add(event.eventTime, lastFocusX, lastFocusY)
    }

    private fun doScroll(event: MotionEvent) {
        val fy = focusY(event, skip = -1)
        val fx = focusX(event, skip = -1)
        // Pan gain: 1 tracks the finger one-to-one, <1 pans slower, >1 faster, 0 freezes the document.
        scrollY = (scrollY + (lastFocusY - fy) * panSensitivity).coerceIn(0f, maxScrollY())
        scrollX = (scrollX + (lastFocusX - fx) * panSensitivity).coerceIn(0f, maxScrollX())
        lastFocusY = fy
        lastFocusX = fx
        // Two fingers also pinch-zoom: a change in span since the last frame scales zoom about the focus.
        val span = spanOf(event)
        if (lastSpan > PINCH_MIN_SPAN_PX && span > PINCH_MIN_SPAN_PX) zoomAbout(fx, fy, span / lastSpan)
        lastSpan = span
        velocityEstimator.add(event.eventTime, fx, fy)
        render()
    }

    private fun maxScrollY(): Float = (layout.totalHeightPx - height).coerceAtLeast(0f)
    private fun maxScrollX(): Float = (layout.contentWidthPx - width).coerceAtLeast(0f)

    private fun endGesture() {
        endGuideDrag(null)
        // Snapshot then clear the mode flags first, so any render() inside a commit (e.g. the
        // rubber-band) sees the gesture already ended and doesn't paint a stale marquee/overlay.
        val wasScrolling = scrolling
        val wasErasing = erasing
        val wasPlacing = placing
        val wasMoving = movingSel
        val wasTransforming = resizing || rotating
        val wasBanding = banding
        val wasTextSelecting = textSelecting
        val wasVspacing = vspacing
        vspacing = false
        scrolling = false
        erasing = false
        placing = false
        movingSel = false
        resizing = false
        rotating = false
        banding = false
        textSelecting = false
        when {
            wasPlacing -> commitPlace()
            wasMoving -> commitMove() // translation applied live; re-home if dropped on another page
            wasTransforming -> Unit  // resize/rotate applied live; finishGesture records them
            wasBanding -> commitBand()
            wasTextSelecting -> Unit // the word range is updated live; the selection just stays put
            // The shift is applied live and finishGesture records it as one undo step; the repaint is
            // what clears the grab-line overlay, which would otherwise stay drawn after the release.
            wasVspacing -> render()
            !wasScrolling && !wasErasing -> commitCurrent()
        }
        moveStartDoc = null
        finishGesture()
        gesturePointerId = -1
        stylusOwner = false
        if (wasScrolling) startFlingIfFast()
    }

    /** Launch a decelerating glide from the just-captured release velocity, if it's fast enough. The
     * seed runs through [Momentum.seed]'s quadratic response so a slow flick barely coasts while a fast
     * swipe flies; [panSensitivity] then scales the glide to match the visual pan gain. */
    private fun startFlingIfFast() {
        if (maxScrollY() <= 0f && maxScrollX() <= 0f) return // nothing to scroll
        val (seedX, seedY) = Momentum.seed(releaseVx, releaseVy, flingStrength, momentumCurve)
        fling.start(seedX * panSensitivity, seedY * panSensitivity)
        if (!fling.isMoving) return
        flinging = true
        flingLastFrameNanos = 0L
        // Drop any paint already queued for the coming vsync: the fling frame will paint it, and both
        // firing would post two buffers for one vsync.
        if (paintPosted) {
            choreographer.removeFrameCallback(paintCallback)
            paintPosted = false
        }
        choreographer.postFrameCallback(flingCallback)
    }

    /** One animation frame of the glide: decay velocity, scroll by the step, stop when done/stuck. */
    private fun onFlingFrame(frameTimeNanos: Long) {
        if (!flinging) return
        // First frame seeds the clock; later frames use the real elapsed time so the glide is
        // frame-rate independent. Clamp big gaps (e.g. after a stall) so one step can't teleport.
        val dt = if (flingLastFrameNanos == 0L) 0f
        else ((frameTimeNanos - flingLastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        flingLastFrameNanos = frameTimeNanos
        val step = fling.advance(dt)
        val prevY = scrollY
        val prevX = scrollX
        scrollY = (scrollY + step.dy).coerceIn(0f, maxScrollY())
        scrollX = (scrollX + step.dx).coerceIn(0f, maxScrollX())
        // Already inside a frame dispatch: paint now rather than deferring to the next vsync. Nothing
        // can have posted [paintCallback] since the glide started ([render] no-ops while flinging), so
        // this is the only buffer posted for this vsync.
        paintPosted = false
        paint()
        // Stop once too slow, or when both axes are pinned at a bound (nowhere left to glide).
        val stuck = scrollY == prevY && scrollX == prevX && dt > 0f
        if (!fling.isMoving || stuck) stopFling() else choreographer.postFrameCallback(flingCallback)
    }

    /** Halt any in-flight glide (a new touch, a cancel, or detachment). */
    private fun stopFling() {
        if (flinging) {
            flinging = false
            choreographer.removeFrameCallback(flingCallback)
        }
        fling.stop()
    }

    /** Record one undo step if this gesture actually changed the document. */
    private fun finishGesture() {
        val start = gestureStartDoc ?: return
        gestureStartDoc = null
        if (doc !== start) {
            history.record(start)
            notifyHistory()
        }
    }

    /** Mean Y of all pointers except [skip] (an index being lifted), in view px. */
    private fun focusY(event: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getY(i); n++ }
        return if (n == 0) event.y else sum / n
    }

    /** Mean X of all pointers except [skip] (an index being lifted), in view px. */
    private fun focusX(event: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getX(i); n++ }
        return if (n == 0) event.x else sum / n
    }

    /** Mean distance of every pointer from the touch focus (view px); a pinch's "size". 0 for <2 pointers. */
    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val fx = focusX(event, skip = -1)
        val fy = focusY(event, skip = -1)
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += hypot(event.getX(i) - fx, event.getY(i) - fy)
        return sum / event.pointerCount
    }

    /**
     * Append every sample for the gesture's pointer — historical first — as pressure-scaled
     * page-local points. Sampling only [pointerIndex] (not pointer 0) is what lets a resting palm
     * coexist with the pen: the palm is a different pointer and is never read here.
     */
    private fun addSamples(event: MotionEvent, pointerIndex: Int, box: PageBox, into: MutableList<StrokePoint>) {
        for (h in 0 until event.historySize) {
            smoother.accept(
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h),
                event.getHistoricalPressure(pointerIndex, h),
            )?.let { into += point(box, it.x, it.y, it.pressure) }
        }
        // The batch's newest sample is always kept, so the drawn line reaches the pen.
        smoother.accept(
            event.getX(pointerIndex), event.getY(pointerIndex), event.getPressure(pointerIndex), force = true,
        )?.let { into += point(box, it.x, it.y, it.pressure) }
    }

    private fun point(box: PageBox, vx: Float, vy: Float, pressure: Float): StrokePoint {
        // A highlighter lays down a broad, constant-width band and ignores pressure; the pen
        // tapers with pressure. (Both persist per-vertex, but the highlighter's values are equal.)
        val width = if (tool == Tool.HIGHLIGHTER) {
            (baseWidthPt * HIGHLIGHTER_WIDTH_FACTOR).toDouble()
        } else {
            val p = if (pressure <= 0f) 1f else pressure
            (baseWidthPt * PressureCurve.factor(p, pressureGamma)).toDouble()
        }
        val (gx, gy) = guided(box, ptX(box, vx), ptY(box, vy))
        return StrokePoint(x = gx, y = gy, width = width)
    }

    private fun commitCurrent() {
        val raw = current ?: return
        current = null
        val wasShaping = shaping
        shaping = false
        // Shape tools emit exact geometry — only freehand samples get thinned.
        // Thin against the page's real px/pt (fit-to-width × zoom), not the zoom alone — on a large
        // screen those differ by 2–4×, and using the zoom leaves visible facets at 100% and below.
        val pxPerPt = layout.boxes.getOrNull(currentPage)?.scale ?: zoom
        var snapped = false
        val pts = if (wasShaping) {
            raw
        } else {
            val thinned = StrokeSimplifier.simplify(raw, StrokeSimplifier.toleranceFor(pxPerPt, strokePrecision))
            // With the recogniser on, a freehand stroke that clearly means a primitive is replaced by
            // clean geometry; anything it doesn't recognise comes through exactly as drawn.
            val shape = if (recognizeShapes && tool == Tool.PEN) {
                ShapeRecognizer.recognize(thinned, baseWidthPt.toDouble())
            } else {
                null
            }
            snapped = shape != null
            shape ?: thinned
        }
        if (pts.size >= 2) {
            // Highlighter and geometric shapes are constant-width → store a single width; the freehand
            // pen keeps its per-vertex pressure. Live line-style/fill are baked in so they round-trip.
            val uniform = tool == Tool.HIGHLIGHTER || wasShaping || snapped
            val stroke = Stroke(
                tool, strokeColor(), "round", pts, uniform,
                lineStyle = currentLineStyle, fill = currentFill,
            )
            appendStroke(currentPage, stroke)
        }
        render()
    }

    /** Highlighter is stored semi-transparent so it round-trips (and renders) translucent. */
    private fun strokeColor(): Int =
        if (tool == Tool.HIGHLIGHTER && (colorArgb ushr 24) == 0xFF) {
            (colorArgb and 0x00FFFFFF) or 0x80000000.toInt()
        } else {
            colorArgb
        }

    // --- audio: stamp strokes while recording, replay them on a play-object tap ------------------

    /**
     * Report the recording behind the topmost stroke under a play-object tap. Reports null (rather
     * than staying silent) when the tap misses or lands on a stroke that was drawn without audio, so
     * the editor can say so instead of leaving the tap looking broken.
     */
    private fun audioTap(event: MotionEvent, pointerIndex: Int) {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val box = layout.pageAt(x + scrollX, y + scrollY) ?: return
        val xPt = ((x + scrollX - box.leftPx) / box.scale).toDouble()
        val yPt = ((y + scrollY - box.topPx) / box.scale).toDouble()
        val page = doc.pages.getOrNull(box.index) ?: return
        val ref = SelectionTester.pickTopmost(page, xPt, yPt)
            ?.let { page.layers.getOrNull(it.layerIndex)?.elements?.getOrNull(it.elementIndex) }
            ?.let { it as? Stroke }
            ?.audioRef()
        onAudioTap?.invoke(ref)
    }

    /** [stroke] with the live recording position stamped on, or unchanged when nothing is recording. */
    private fun withAudioStamp(stroke: Stroke): Stroke =
        audioStamp?.invoke()?.let(stroke::withAudio) ?: stroke

    /** Append [stroke] to the active (or top) layer of page [pageIndex], rebuilding the model. */
    private fun appendStroke(pageIndex: Int, stroke: Stroke) {
        val pages = doc.pages.toMutableList()
        val page = pages[pageIndex]
        val layers = page.layers.ifEmpty { listOf(Layer(emptyList())) }.toMutableList()
        val target = resolvedActiveLayer(page).coerceIn(0, layers.lastIndex)
        // Stamping here rather than at each commit site covers freehand, shapes and splines alike.
        layers[target] = Layer(layers[target].elements + withAudioStamp(stroke), layers[target].name)
        pages[pageIndex] = page.copy(layers = layers)
        doc = doc.copy(pages = pages)
        relayout() // rebuild boxes so they reference the updated pages, not stale ones
    }

    /** The layer new ink lands on for [page]: [activeLayerIndex] when in range, else the top layer. */
    private fun resolvedActiveLayer(page: Page): Int =
        if (activeLayerIndex in page.layers.indices) activeLayerIndex else page.layers.lastIndex

    /**
     * Apply the eraser disc to page [pageIndex] in the current [eraserMode], skipping hidden
     * layers ([PageEraser]). Returns true if anything on the page changed.
     */
    private fun eraseOnPage(pageIndex: Int, px: Double, py: Double, radius: Double): Boolean {
        val page = doc.pages.getOrNull(pageIndex) ?: return false
        val hidden = page.layers.indices.filter { isLayerHidden(pageIndex, it) }.toSet()
        val erased = PageEraser.erase(page, px, py, radius, eraserMode, hidden) ?: return false
        val pages = doc.pages.toMutableList()
        pages[pageIndex] = erased
        doc = doc.copy(pages = pages)
        relayout()
        return true
    }

    // --- surface + rendering -------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) = render()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        relayout()
        // A guide restored from settings before the first layout had no viewport to centre on; now
        // there is one, so place it for real rather than leaving it wherever the fallback guessed.
        pendingGuide?.let { pendingGuide = null; placeGuide(it) }
        render()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) = stopFling()

    override fun onDetachedFromWindow() {
        stopFling()
        choreographer.removeFrameCallback(paintCallback)
        paintPosted = false
        inkCache.clear()
        super.onDetachedFromWindow()
    }

    private fun relayout() {
        layout = PageStacker.stack(doc.pages, width, GAP_PX, zoom, columns)
        scrollY = scrollY.coerceIn(0f, maxScrollY())
        scrollX = scrollX.coerceIn(0f, maxScrollX())
    }

    /**
     * Ask for one redraw from a background thread, collapsing a burst into a single frame. Several
     * tiles of the same pan often finish within one frame's time; posting a [render] for each would
     * repaint the whole viewport that many times over.
     */
    private fun requestRender() {
        if (renderPosted.getAndSet(true)) return
        post {
            renderPosted.set(false)
            render()
        }
    }

    /**
     * Ask for a repaint at the next display frame, collapsing everything asked for in between into
     * one [paint].
     *
     * Painting is **never** done straight from an input handler. A stylus/touch digitiser reports
     * far faster than the display refreshes (240 Hz against 120 Hz on the large tablets), so a
     * synchronous paint per event posts two or more buffers per vsync. The compositor latches
     * whichever happens to be newest at each vsync, so the position it shows walks back and forth
     * between samples instead of advancing — the flicker seen when zoomed in on a big screen, where
     * a paint is slow enough to keep several buffers in flight. Pacing to the [Choreographer] posts
     * exactly one buffer per vsync, in phase, so every frame shown is the newest state.
     */
    private fun render() {
        // A glide already paints once per vsync from [onFlingFrame]; posting a second callback for the
        // same frame would post two buffers per vsync and bring back exactly the buffer-walk flicker
        // above. This is the common case when zoomed in and panning: every PDF tile that lands calls
        // back into [requestRender] mid-fling. Whatever asked for this repaint is shown by the glide's
        // own frame anyway, so dropping the request loses nothing.
        if (paintPosted || flinging) return
        paintPosted = true
        choreographer.postFrameCallback(paintCallback)
    }

    /**
     * Lock the surface for one frame, preferring the **GPU** canvas.
     *
     * [SurfaceHolder.lockCanvas] hands back a *software* canvas: every pixel of the window is
     * rasterised and blended on the CPU, so a frame costs time proportional to the window's pixel
     * area no matter how little is on the page. On a large tablet that is several million pixels a
     * frame, which is why flicking pages full-screen crawls while the identical gesture in a
     * split-screen (half the pixels) stays smooth. [SurfaceHolder.lockHardwareCanvas] records the
     * same draw calls and replays them on the GPU, where fill rate is essentially free and the
     * cached page bitmaps become plain textured blits.
     *
     * Falls back to the software canvas if the hardware one is unavailable (no GL context, an
     * emulator without a working renderer), so the view still paints rather than going black.
     */
    private fun lockCanvasForFrame(): Canvas? =
        try {
            holder.lockHardwareCanvas()
        } catch (_: IllegalStateException) {
            null
        } ?: holder.lockCanvas()

    private fun paint() {
        paintPosted = false
        if (!holder.surface.isValid) return
        val canvas = lockCanvasForFrame() ?: return
        try {
            canvas.drawColor(backdropColor)
            val visible = layout.visible(scrollY, height.toFloat())
            for (box in visible) {
                BackgroundRenderer.draw(
                    canvas, box, scrollX, scrollY, pdfBitmapFor(box), pdfTilesFor(box),
                    width.toFloat(), height.toFloat(),
                )
                drawPageElements(canvas, box)
            }
            inkCache.retain(visible.mapTo(HashSet()) { it.index })
            retainPdfPins(visible)
            prefetchAround(visible)
            drawCurrent(canvas)
            drawTextSelection(canvas)
            selection?.let { drawSelectionBox(canvas, it) }
            if (selectedPages.isNotEmpty()) drawPageSelection(canvas)
            if (pageDragIndex >= 0) drawPageDrag(canvas)
            if (banding) drawBand(canvas)
            if (vspacing) drawVerticalSpaceGuide(canvas)
            drawGuide(canvas)
            if (hovering && showHover && current == null) drawHover(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        reportCurrentPage()
        reportScroll()
    }

    /**
     * The rasterised background for a `pdf`-backed page at its on-screen width, or null. Never
     * rasterises inline — a miss returns whatever resolution is already cached (or nothing) and the
     * sharp version arrives via [PdfPageCache.onPageReady], so a frame is never stalled by the PDF.
     */
    private fun pdfBitmapFor(box: PageBox): Bitmap? {
        val bg = box.page.background as? Background.Pdf ?: return null
        return pdfSource?.request(bg.pageNo, box.widthPx.toInt())
    }

    /**
     * The full-resolution tiles for the visible part of a `pdf`-backed page, drawn over the
     * (upscaled) whole-page bitmap. Empty until the zoom passes the whole-page raster ceiling, so
     * normal reading costs exactly what it did before. Like [pdfBitmapFor] this never blocks: a tile
     * that isn't cached yet is queued and arrives on a later frame.
     */
    private fun pdfTilesFor(box: PageBox): List<PdfTile> {
        val bg = box.page.background as? Background.Pdf ?: return emptyList()
        val src = pdfSource ?: return emptyList()
        if (box.widthPx <= 0f || box.heightPx <= 0f) return emptyList()
        val x = scrollX - box.leftPx
        val y = scrollY - box.topPx
        return src.requestTiles(
            bg.pageNo, box.widthPx.toInt(),
            (x / box.widthPx).coerceIn(0f, 1f),
            (y / box.heightPx).coerceIn(0f, 1f),
            ((x + width) / box.widthPx).coerceIn(0f, 1f),
            ((y + height) / box.heightPx).coerceIn(0f, 1f),
        )
    }

    /**
     * Tell the PDF cache which pages are still on screen, so it stops protecting the tiles of pages
     * we have scrolled past. Uses the PDF page numbers, the same key [pdfTilesFor] pins under.
     */
    private fun retainPdfPins(visible: List<PageBox>) {
        val src = pdfSource ?: return
        src.retain(visible.mapNotNullTo(HashSet()) { (it.page.background as? Background.Pdf)?.pageNo })
    }

    /**
     * Warm the pages just outside the viewport so scrolling meets a filled cache rather than a
     * rasterise. One page either side is enough to cover a flick at reading speed.
     */
    private fun prefetchAround(visible: List<PageBox>) {
        val src = pdfSource ?: return
        if (visible.isEmpty()) return
        val first = visible.first().index
        val last = visible.last().index
        for (i in intArrayOf(first - 1, last + 1)) {
            val box = layout.boxes.getOrNull(i) ?: continue
            val bg = box.page.background as? Background.Pdf ?: continue
            src.prefetch(bg.pageNo, box.widthPx.toInt())
        }
    }

    /**
     * Paint one page's ink. The [InkCache] handles it as a single blit whenever it can; a gesture
     * that rewrites the page every frame (drag/resize/rotate/erase) would only thrash the raster, so
     * those fall through to submitting elements directly — as does any page too large to cache.
     */
    private fun drawPageElements(canvas: Canvas, box: PageBox) {
        val hidden = if (hiddenLayers.isEmpty()) {
            emptySet()
        } else {
            box.page.layers.indices.filterTo(HashSet()) { isLayerHidden(box.index, it) }
        }
        if (inkCacheUsable &&
            inkCache.draw(canvas, box, scrollX, scrollY, hidden, strokePainter, elementRenderer)
        ) {
            return
        }
        PageRenderer.drawElements(
            canvas, box.page, box.scale, box.leftPx - scrollX, box.topPx - scrollY,
            strokePainter, elementRenderer, hidden, visibleBounds(box),
        )
    }

    /** False during gestures that rewrite the page model each frame, where caching would thrash. */
    private val inkCacheUsable: Boolean
        get() = !movingSel && !resizing && !rotating && !erasing

    /**
     * The viewport in this page's local pt space, so [PageRenderer] can drop elements that can't be
     * on screen. At high zoom a page spans many screens, where almost every stroke is off-screen and
     * submitting it costs thousands of canvas calls Skia would only clip away.
     */
    private fun visibleBounds(box: PageBox): Bounds? {
        if (box.scale <= 0f) return null
        val s = box.scale.toDouble()
        val left = (scrollX - box.leftPx) / s
        val top = (scrollY - box.topPx) / s
        return Bounds(left, top, left + width / s, top + height / s)
    }

    private fun drawCurrent(canvas: Canvas) {
        val pts = current ?: return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        strokePainter.draw(
            canvas, pts, tool, strokeColor(), box.scale, box.leftPx - scrollX, box.topPx - scrollY,
            currentLineStyle, currentFill,
        )
    }

    /** Highlight the selected PDF-text word boxes (the same frame as strokes, so it tracks scroll/zoom). */
    private fun drawTextSelection(canvas: Canvas) {
        val index = pdfTextIndex ?: return
        if (textSelPage < 0 || textSelAnchor < 0) return
        val box = layout.boxes.getOrNull(textSelPage) ?: return
        for (w in index.rangeBoxes(textSelPage, textSelAnchor, textSelFocus)) {
            val l = (w.left * box.scale + box.leftPx - scrollX).toFloat()
            val t = (w.top * box.scale + box.topPx - scrollY).toFloat()
            val r = (w.right * box.scale + box.leftPx - scrollX).toFloat()
            val b = (w.bottom * box.scale + box.topPx - scrollY).toFloat()
            canvas.drawRect(l, t, r, b, textSelectPaint)
        }
    }

    /** Draw the dashed selection outline (padded a little), the four resize handles, and — for an
     * all-stroke selection — the top rotate knob. */
    private fun drawSelectionBox(canvas: Canvas, sel: ActiveSelection) {
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return
        val l = (b.left * box.scale + box.leftPx - scrollX).toFloat() - SELECT_PAD_PX
        val t = (b.top * box.scale + box.topPx - scrollY).toFloat() - SELECT_PAD_PX
        val r = (b.right * box.scale + box.leftPx - scrollX).toFloat() + SELECT_PAD_PX
        val bot = (b.bottom * box.scale + box.topPx - scrollY).toFloat() + SELECT_PAD_PX
        canvas.drawRect(l, t, r, bot, selectionFillPaint)
        canvas.drawRect(l, t, r, bot, selectionStrokePaint)
        // Corner resize handles.
        for (hx in floatArrayOf(l, r)) for (hy in floatArrayOf(t, bot)) {
            canvas.drawCircle(hx, hy, HANDLE_DRAW_PX, handlePaint)
        }
        // Rotate knob poking out midway from the right edge (strokes only).
        if (isAllStrokes(sel)) {
            val midY = (t + bot) / 2f
            val knobX = r + ROTATE_ARM_PX
            canvas.drawLine(r, midY, knobX, midY, handleArmPaint)
            canvas.drawCircle(knobX, midY, HANDLE_DRAW_PX, handlePaint)
        }
    }

    /** Draw the hover preview: a ring in the pen colour at the stylus's current hover point. */
    private fun drawHover(canvas: Canvas) {
        val r = (baseWidthPt * 3f).coerceIn(6f, 28f)
        hoverPaint.color = (colorArgb and 0x00FFFFFF) or HOVER_ALPHA
        canvas.drawCircle(hoverX, hoverY, r, hoverPaint)
    }

    /** Draw the vertical-space grab line across the page being reflowed (view px). */
    private fun drawVerticalSpaceGuide(canvas: Canvas) {
        val box = layout.boxes.getOrNull(vspacePage) ?: return
        val left = box.leftPx - scrollX
        val right = left + box.widthPx
        canvas.drawRect(left, vspaceLineViewY - 1f, right, vspaceLineViewY + 1f, selectionStrokePaint)
    }

    /** Draw the live marquee (view px): a rectangle, or the traced lasso path in lasso mode. */
    private fun drawBand(canvas: Canvas) {
        if (lassoMode && lassoPts.size >= 4) {
            lassoPath.reset()
            lassoPath.moveTo(lassoPts[0], lassoPts[1])
            var i = 2
            while (i < lassoPts.size) { lassoPath.lineTo(lassoPts[i], lassoPts[i + 1]); i += 2 }
            lassoPath.close()
            canvas.drawPath(lassoPath, bandFillPaint)
            canvas.drawPath(lassoPath, selectionStrokePaint)
            return
        }
        val l = min(bandX0, bandX1)
        val t = min(bandY0, bandY1)
        val r = max(bandX0, bandX1)
        val bot = max(bandY0, bandY1)
        canvas.drawRect(l, t, r, bot, bandFillPaint)
        canvas.drawRect(l, t, r, bot, selectionStrokePaint)
    }

    internal companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        const val PAGE_SIZE_MIN_PT = 72.0     // 1 in — floor on a page dimension
        const val PAGE_SIZE_MAX_PT = 14400.0  // 200 in — ceiling on a page dimension
        const val GAP_PX = 24f
        const val ERASER_RADIUS_PX = 18f
        /** Highlighter width as a multiple of the pen's base width: broad, flat, and pressure-independent. */
        const val HIGHLIGHTER_WIDTH_FACTOR = 6f
        const val BACKDROP = 0xFF3A3A3A.toInt()
        const val ZOOM_STEP = 1.25f
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 10f
        /** Below this two-finger span (view px) the pinch ratio is too noisy to zoom by, so it's ignored. */
        const val PINCH_MIN_SPAN_PX = 40f
        const val TAP_SLOP_PX = 16f
        const val SELECT_PAD_PX = 6f
        const val MOVE_GRAB_PAD = 8.0
        const val HANDLE_HIT_PX = 30f      // touch radius for grabbing a resize/rotate handle
        const val HANDLE_DRAW_PX = 7f      // drawn radius of a handle dot
        const val ROTATE_ARM_PX = 40f      // gap from the right edge out to the rotate knob
        const val MIN_RESIZE = 0.05        // clamp on the live uniform-resize factor
        const val MAX_RESIZE = 20.0
        const val PASTE_OFFSET_PT = 12.0   // paste/duplicate nudge so copies don't hide the original
        const val SELECTION_COLOR = 0xFF2060E0.toInt()

        /** Outline colour of the setsquare/compass overlay — amber, so it reads as an instrument
         * laid on the page rather than as a selection. */
        const val GUIDE_COLOR = 0xFFE08A20.toInt()

        /** Alpha of the wash filling the setsquare's body — enough to see, faint enough to draw over. */
        const val GUIDE_FILL_ALPHA = 0x22
        /** Alpha of the wash inside a selection outline. */
        const val SELECTION_FILL_ALPHA = 0x14
        /** Alpha of the wash inside the drag-select band. */
        const val BAND_FILL_ALPHA = 0x22

        // Which part of the guide a finger is holding.
        const val GUIDE_DRAG_NONE = 0
        const val GUIDE_DRAG_BODY = 1
        const val GUIDE_DRAG_TIP = 2
        const val SELECTION_FILL = 0x142060E0
        const val BAND_FILL = 0x222060E0
        /** Translucent blue wash over selected PDF-text word boxes (like a text highlight). */
        const val TEXT_SELECT_FILL = 0x552196F3
        const val HOVER_ALPHA = 0xB0000000.toInt()
        const val TEX_W_PT = 120.0
        const val TEX_H_PT = 40.0
        const val IMG_MAX_PT = 240.0

        /** A fresh one-page document — what a new tab starts on (see `com.xopp.android.tabs`). */
        fun blankDocument() = Document(pages = listOf(blankPage()))

        fun blankPage() = Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(AndroidColor.WHITE, "graph"), listOf(Layer(emptyList())))
    }
}
