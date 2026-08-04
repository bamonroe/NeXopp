# Xopp — a stylus-first Xournal++ editor for Android

**Xopp** opens, edits, and saves [Xournal++](https://github.com/xournalpp/xournalpp) `.xopp`
files on Android. Draw and handwrite with a pen/stylus on a tablet or phone, then save back to
the **same `.xopp` format** so the file round-trips cleanly to and from desktop Xournal++ on
Linux. The guiding principle is **format fidelity and round-trip safety**: a file edited on
Android reopens correctly on the desktop, and vice versa.

- What the project is and how to work in it: [`CLAUDE.md`](CLAUDE.md).
- How it works internally (the `.xopp` schema, data path, model): [`docs/architecture.md`](docs/architecture.md).
- Build/emulator tooling: [`docs/tools.md`](docs/tools.md).
- What's next (active tasks): [`TODO.toml`](TODO.toml); what's already shipped: [`FINISHED.toml`](FINISHED.toml). These are TOML task files driven by the `todo` skill — run `scripts/todo.sh list` or `scripts/todo.sh stats` to read them.

> Status: the `.xopp` read/write core and its tests are in place, and the Android editor is
> functional — pen/highlighter/eraser drawing with pressure, colour and width pickers, undo/redo,
> zoom, pan, a page navigator (add/remove/jump, 1-4 pages per row), on-device authoring of text/image/LaTeX elements,
> LaTeX math rendering, multi-page documents with layers and backgrounds, and PDF import and
> export. The controls live in a vertical rail down the left edge. Run `scripts/todo.sh list` for what's next.

## Requirements

Everything builds through the **shared Android toolchain in `/data/android`** (a baked
`android-builder:local` container), so the only host requirement is Docker. You do **not** need
a local JDK, Android SDK, or Gradle. To run the app on a virtual device you additionally need a
KVM-capable host for that directory's headless emulator (see [`docs/tools.md`](docs/tools.md)).

## Build & test

The one command you need:

```sh
scripts/build.sh
```

This runs the full check loop through the shared toolchain container — **unit tests + a debug
APK**. The SDK image is already baked; the first run only downloads Gradle and dependencies
into a per-project `.gradle-cache/`, so later runs are fast.

Common variants:

```sh
scripts/build.sh testDebugUnitTest     # JVM unit tests only (no device needed)
scripts/build.sh clean assembleDebug   # clean build of the debug APK
scripts/build.sh <any gradle tasks>    # arbitrary Gradle tasks in the container
```

Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Test report: `app/build/reports/tests/testDebugUnitTest/index.html`

The unit tests cover the `.xopp` round-trip (the colour codec, every element type, XML escaping,
model reserialization, a gzip round-trip, the PDF-background on-disk shape, and a fixture-driven
`FormatDriftTest` asserting schema coverage, and an `XmlEqualityRoundTripTest` that checks the
XML we emit still matches the desktop-written source byte-for-byte once normalized) plus the pure `render/` geometry (page layout,
gridlines, page ops, eraser hit-testing, text layout, undo/redo history). If a real
desktop-generated `udiff.xopp` is present at the repo root, an extra test round-trips it end to end
(it self-skips when absent).

## Run on a device / emulator

Install the debug APK on a connected device or a running emulator:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.xopp.android/.MainActivity
```

Emulator setup (AVD creation, headless launch, KVM notes) lives in
[`docs/tools.md`](docs/tools.md).

## Using the app

- **Open** — the top-bar **menu** (the ☰ button, top right) has **Open**; it launches the system
  file picker; choose a file. It's read in place via the Storage Access Framework. **Open accepts
  four kinds of file and works out which is which from the file's contents, not its name**: a
  gzip-compressed `.xopp` (the usual desktop format), a single-file zipped `.xopp` package (its
  bundled PDF travels inside), an uncompressed Xournal++ XML file, and a **plain PDF** — picking a
  PDF opens it as a fresh annotatable document exactly as **Import PDF** below does. A file that
  is none of these is refused with an "Open failed" notice. Whichever `.xopp` container it came
  from is remembered, so a later Save writes it back in the same one.
  **Files on remote shares work too** — anything the system picker lists, including SSHFS, FTP,
  WebDAV and cloud providers mounted as storage. Those reads can be slow, so the document is
  fetched in the background behind an "Opening…" note and only appears once it has fully landed;
  a link that drops mid-transfer leaves the app as it was and reports the failure. Every
  page is shown, one above the next, each drawn with its own background ruling (plain, lined,
  ruled, graph, or dotted) and all of its layers — including strokes, text boxes, images, and
  LaTeX images (rendered as real math — fractions, super/subscripts, roots, and Greek/operator
  symbols; malformed formulae fall back to their source text). If the `.xopp` was made by
  annotating a PDF, its **PDF background is reloaded automatically** — the reference the file
  stores is resolved and the PDF pages render underneath your annotations again, so a saved
  project reopens intact. If the referenced PDF can't be found (e.g. a desktop path that doesn't
  exist on the device), those pages open blank and a notice is shown.
- **Tabs — several documents open at once** — every document you open lives in its own **tab**, and
  the **tab strip** under the top bar is always shown — even with a single document open — so the
  same tab controls are always in the same place. Tap a tab to switch to that document;
  tap the **✕** on the tab you're looking at to close it; tap **+** at the end of the strip — or
  **New document** in the ☰ menu — to start a fresh blank document in a new tab. The tabs, the
  **✕** and the **+** are all full finger-sized targets (Material's 48dp minimum), so switching or
  closing a document works with a fingertip and doesn't need a stylus. **Open** always
  opens into a new tab rather than replacing what you're working on. Each tab carries its own save
  format, its own background PDF and the page you were on, so switching back lands you where you
  left off. Closing the last tab leaves you on a fresh blank document. One thing to note: **undo
  history doesn't follow a tab switch** — the incoming document starts with a clean undo history,
  though all of its content and unsaved edits are intact.
- **Split view — two documents side by side** — **Split view** in the ☰ menu divides the drawing
  area into a left and a right **pane**, each showing its own document. Drag the bar down the
  middle to rebalance the two halves (it's a finger-wide grab strip, and neither pane can be
  squeezed below about a sixth of the width). Each pane has its **own tab strip**, so the two
  halves hold entirely separate sets of open documents, and each keeps its own scroll position,
  zoom, current page, layers and undo history. Handy for copying between two notebooks, or for
  writing notes beside a PDF you're reading.

  The toolbar and the ☰ menu always drive **the pane you last touched** — tap or draw in a pane to
  give it focus, and Save, Import PDF, the pen settings and undo/redo then apply to that document.
  Choosing **Close split view** hands the whole area back to the left pane; the right pane's tabs
  are kept, so turning split view on again brings the same documents back.
- **Drag a tab sideways to reorder it** — press a tab and slide it left or right along its strip to
  move it past its neighbours; the order sticks and is restored with the rest of the session. A drag
  only takes over once your finger has actually moved, so a plain tap still switches document and a
  stationary hold still opens the long-press menu below. Dragging reorders **within** a strip; use
  that menu to send a tab to the other pane.
- **Long-press a tab to send it to the other view** — holding a tab pops up a small menu with two
  entries. **Move to other view** takes that document out of this pane and opens it in the other
  one; **Mirror on other view** leaves it where it is and opens a **second view of the same
  document** in the other pane. Either one opens split view automatically if it was closed.
- **A mirrored document is live in both panes** — the two mirrored tabs are two windows onto one
  document, not two copies: a stroke, an erase or a page change made on one side appears on the
  other **immediately**, and both save back to the same file. The views stay independent in every
  other respect — each keeps its own scroll position, zoom and current page, so you can work at the
  top of a page on the left while watching the bottom of it on the right, or keep a diagram in view
  while writing about it further down. Undo lives in the pane you are editing in: the other view
  drops its undo history when it takes an edit, so an undo can't quietly discard the work the other
  side just did.

  Because tabs are named after files, and different files are often named alike, mirrored tabs are
  marked with a small **coloured dot**. Tabs sharing a dot colour are views of the *same* document —
  two "notes.xopp" tabs with no dots are two different files that merely share a name.
- **Tabs are restored when you reopen the app** — the set of open tabs is cached on the device
  (including edits you hadn't saved yet), so closing the app and starting it again brings back
  exactly the tabs you had, with the same one showing. This is a convenience cache, **not a
  substitute for saving**: your `.xopp` file on disk is still only written when you **Save**, and
  that file is the only thing desktop Xournal++ ever sees.
- **Import PDF** — the menu's **Import PDF** first asks **how the PDF should join the document**:
  **Replace** (the PDF's pages *become* the document, discarding the pages currently open) or
  **Append** (the PDF's pages are added *after* the pages already open, keeping their annotations —
  and it's a single undo away). A `.xopp` can reference just **one** background PDF, so appending onto a
  document that *already* has one **merges the two into a single joined PDF** — the incoming PDF's
  pages are added to the end of the existing background PDF, that joined file becomes the document's
  one background source, and the appended pages are renumbered against it. Repeat appends keep
  composing onto the joined PDF without touching the original files, and the joined PDF is what the
  saved `.xopp` links to (or embeds, for a zipped **Save As**), so the result reopens in desktop
  Xournal++ with every appended page's background intact. Appending onto a brand-new, untouched blank page drops that stray blank sheet. Choosing a
  mode launches the picker filtered to PDFs; the import gives you **one page per PDF page**, each PDF
  page rasterised and shown as the page background (à la desktop Xournal++ PDF annotation). Draw on top as usual; the strokes are
  kept separate from the PDF and the `pdf` backgrounds round-trip when you **Save** the `.xopp`.
  The source PDF's reference is recorded in the saved file (as an absolute reference to the picked
  PDF), so reopening the `.xopp` later **reloads that PDF** and shows the same backgrounds again —
  no need to re-import.
- **Select text (PDF)** — for an imported PDF that carries a real text layer (i.e. not a pure scan),
  the Tool pop-up's **Select text (PDF)** tool lets you **drag across the page to select the
  underlying text**; the selected words highlight, and a **Copy** button puts them on the system
  clipboard to paste elsewhere. It reads the PDF's own text — no OCR — so scanned image-only PDFs
  have nothing to select (OCR for those is planned). The selection is view-only and doesn't change
  the document.
- **Vertical space** — pick **Vertical space** from the rail to reflow a page: **drag down** on the
  page to insert blank vertical space, pushing everything below the grab line down with your finger,
  or **drag up** to close a gap and pull that content back. A dashed guide shows the grab line while
  you drag; only objects whose **top** edge is below the line move (one the line passes through stays
  put rather than being torn), and **all layers move together**. The whole drag is a single undo step,
  and since it only shifts coordinates it round-trips to desktop Xournal++ unchanged.
- **Draw** — the controls live in a **vertical rail down the left edge**. It starts with eight
  **tool slots**, each standing for a group of related tools and showing the one that group is
  currently set to: **Draw** (pen · highlighter), **Eraser** (partial · whole stroke), **Line** (line · arrow · double arrow · spline), **Shape**
  (rectangle · ellipse · coordinate axis), **Pan**, **Select** (rectangle · lasso · select text), **Insert** (text · LaTeX ·
  image), and **Vertical space**. **Tap** a slot to switch to the tool it shows — the active slot is highlighted — or
  **long-press** it to pick a different member, which both switches to that tool and re-faces the
  slot. Those per-slot choices are **remembered across app restarts**, so the rail comes back the
  way you left it. The remaining buttons — **Colour**, **Size**, **Style**, **Guides**, **Layers**, **Zoom**,
  **Background**, **Pages** — each open a small pop-up anchored
  to their own button (opening to the right of the rail). Pick **Pen** or **Highlighter** and draw with
  **one finger or the stylus**; pen pressure sets stroke width. The **Highlighter** instead lays down a **broad,
  constant-width translucent band** (pressure-independent, ~6× the pen width) that shows the page
  through it — it saves as a `highlighter` stroke and reopens the same way in desktop Xournal++.
  Choose a **colour** (swatches) and a base **width**
  from the other two pop-ups. The Colour pop-up ends with an editable **custom slot** (marked with a
  pencil): **tap** it to draw with its current colour, or **long-press** it to open a picker —
  a saturation/value square over a hue slider plus a `#RRGGBB` hex field — to set any colour. The Size
  pop-up offers three width **slots**, each drawn as a **filled dot sized to that slot's width** (the
  widest slot fills the row, the rest scale down in proportion) next to its exact point size, so the
  three read as a tip-size ladder rather than three arbitrary letters: **tap** a slot to draw with it, or
  **long-press** a slot to open a resize dialog (0.5 → 15 pt) that redefines that slot's width — drag
  the **slider** for a broad sweep, tap **−** / **+** to nudge it 0.1 pt at a time, or type an exact
  point size into the **text field**. Below the swatches the Colour pop-up shows a **Recent** row —
  the last seven colours you picked, most-recent-first — so a colour mixed in the custom picker stays
  one tap away after you move on. That same palette — swatches, custom slot and Recent row — is what
  the text-box dialog and the selection recolour menu offer, so a colour picked anywhere shows up in
  the recents everywhere. The custom colour, the three widths, the recent row, and the
  colour/width you were last drawing with are all remembered across restarts, so the app reopens with
  the pen you left off with. New strokes land on the **active layer**
  (see **Layers** below) of whichever page you draw on.
- **Shapes** — the Tool pop-up also offers **Line**, **Arrow**, **Double arrow**, **Rectangle**,
  **Ellipse**, and **Coordinate axis**. Pick one and **drag** from one corner/endpoint to the other; a live preview follows your finger and the
  shape commits on release. Shapes are saved as ordinary strokes in the current pen colour and width,
  so they round-trip to desktop Xournal++ like any other stroke. A double arrow gets a head at each
  end; a coordinate axis puts its origin where the drag started and runs an arrowed x and y axis out
  to the drag's width and height.
- **Spline** — the Line slot also offers **Spline**, for a smooth curve through points you place one
  at a time. **Tap** to drop a control point; **drag** away from a tap instead of lifting to pull out
  a tangent handle that bows the curve through that point (lift where you want the curve to lean).
  Keep tapping to extend it — the whole curve previews live as you go. **Double-tap**, or press
  **Enter** on a hardware keyboard, to finish; **Escape** throws the curve away, and switching to
  another tool commits whatever you have so far. The result is one ordinary constant-width stroke in
  the current pen colour and width, so it round-trips to desktop Xournal++ like any other stroke.
- **Shape recognition** — turn it on from the rail's **Shape recognition** button (the triangle; it
  tints while on, and the state is the same persisted setting as **Settings → Stylus → Shape
  recognition**, so you can flip it mid-page without leaving the editor) and a freehand stroke is
  snapped, the moment you lift, to the shape it clearly resembles: a straight **line**, an **arrow**
  (shaft plus a barb folded back over it), a **circle/ellipse**, a **rectangle** (squared to its
  bounding box when you drew it roughly upright), a **triangle**, or a short **polyline**. Anything
  the recogniser doesn't recognise — handwriting above all — is kept exactly as you drew it. The
  result is one ordinary constant-width stroke, so it round-trips to desktop Xournal++ like any
  other. The toggle is off by default and only affects the pen, never the highlighter.
- **Snapping** — two optional aids under **Settings → Editor**. **Snap to grid** pulls the start and
  end of a shape drag onto the page background's ruling, so lines and boxes line up with the paper: a
  **graph** or **dotted** page snaps both axes to its squares, a **lined**/**ruled** page snaps only
  the vertical position (it rules no vertical lines), and a plain page snaps nothing. **Snap
  rotation** makes the selection's rotate handle step in 15° increments — handy for turning something
  exactly upright or square. Both are off by default, and neither changes what is written to the
  file: the result is still ordinary stroke geometry.
- **Setsquare & compass** — the **Guides** pop-up on the rail lays a physical-feeling drawing
  instrument on the page: a **Setsquare** (a 30/60/90 geometry triangle) or a **Compass** (a circle
  of a chosen radius). While one is on the page, anything you draw within about a quarter-inch of
  its edge is ruled onto that edge — so a freehand pen stroke along the setsquare's side comes out
  perfectly straight, and a stroke swept around the compass comes out as a clean arc. Draw further
  away and the pen behaves normally, so you don't have to keep switching the guide off. A **finger**
  drags the guide around by its body and re-poses it by the amber handle at its tip: for the
  setsquare that rotates and lengthens it (with **Snap rotation** on, in 15° steps), for the compass
  it opens the radius. You can hold the guide steady with one hand while the pen rules along it,
  exactly as on paper. Choose **Off** to take it away. The guide is only an input aid — nothing
  about it is written to the file, and what you draw is an ordinary stroke that round-trips to
  desktop Xournal++ like any other. Which guide you had on is remembered across launches.
- **Drawing guides (setsquare & compass)** — the **Guides** rail button lays a virtual instrument on
  the page: a **Setsquare** (a 30/60/90 geometry triangle) or a **Compass** (a circle). Anything you
  draw within about a quarter-inch of the instrument's edge is ruled onto it, so a freehand stroke
  along the setsquare comes out perfectly straight and a stroke swept around the compass comes out as
  a clean arc of that radius; move the pen away from the edge and it draws freehand again. Slide the
  guide with a **finger** placed on its **body** — inside the setsquare's shaded triangle, or on the
  compass's centre dot — and drag its **amber tip handle** to re-pose it: that rotates and resizes the
  setsquare (with **Snap rotation** on it lands on 15° steps) and opens or closes the compass. The
  edges themselves are left free to draw against, so ruling along the outside of the setsquare never
  drags it out from under your pen, and you can go on holding it steady while you draw. Choose
  **Off** to take it away. The guide is purely an input aid: it is never written to the `.xopp` file,
  so what you draw with it is ordinary stroke geometry. Which guide is out is remembered across
  launches.
- **Line style & fill** — the **Style** pop-up sets the pattern for strokes and shapes you draw next:
  **Solid**, **Dashed**, **Dash-dot**, or **Dotted**; and a **Fill** switch with an opacity slider
  (1–100%) that floods the inside of a closed stroke or shape with the pen/highlighter colour.
  Turning fill off keeps the opacity you last picked, so switching it back on restores it. The
  switch and its opacity are remembered across launches. Both save on the `<stroke>` element
  (`style` / `fill`) and reopen the same way in desktop Xournal++.
- **Erase** — tap the rail's **Eraser** slot and drag over strokes. **Long-press** the slot to pick
  which eraser it stands for: **Eraser (partial)** rubs out just the part of a stroke the eraser
  passes over, splitting it into the surviving pieces; **Eraser (whole stroke)** removes any stroke
  the eraser touches entirely. Like every tool slot, the choice is remembered across restarts. The
  eraser has no size of its own — its tip follows the **Size** pop-up's three width slots (about six
  times the pen width, so the rubber is always wider than the ink it removes), measured in document
  points, so it rubs out the same amount of ink whatever the zoom. The eraser only affects the
  **selected layer** — ink on other layers is left alone — and hidden layers are never erased. If your stylus has an **eraser tip** (the flip-over
  end), using it erases no matter which tool is selected; so does holding the stylus **barrel button**
  (configurable — see **Settings**). Double-clicking the barrel button with the pen lifted off the
  glass runs its own action — undo by default (see **Settings**).
- **Layers** — the **Layers** pop-up manages the visible page's layers (top of the list = top of the
  page). Each row can **make the layer active** (tap its name — new ink lands there, marked with a
  filled dot), **show/hide** it in the editor (the eye toggle — hiding is view-only and never changes
  the file), **reorder** it up/down (z-order), **merge it down** into the layer below (the merge
  button — the two layers' contents combine in z-order, the lower layer keeps its name, and the
  emptied upper layer goes away; disabled on the bottom layer), **rename** it, or **delete** it (a
  page always keeps at least one layer). **Add layer** puts a fresh empty layer on top. With something selected, each row
  also shows a **move-selection-here** button. Layer names round-trip via the `<layer name>` attribute;
  every structural change is undoable.
- **Stylus** — the app is stylus-first. Rest your **palm** on the screen while you write: once the pen
  is down, finger/palm touches are ignored for drawing (a second finger still pans). A hovering stylus
  shows a **preview ring** where the tip will land. Pen **pressure** sets stroke width, with a
  configurable feel, tapering more deeply at a light touch to match desktop Xournal++. Handwriting is
  **smoothed** as you write — digitiser wobble in both position and pressure is filtered out, and
  redundant points are dropped when the pen lifts, so strokes look clean and files stay small.
  All of this is tuned in **Settings** below.
- **Select** — the rail's **Select** slot selects objects the way desktop Xournal++ does. The
  marquee shape is the tool itself: **long-press** the slot to pick **Select rectangle** (drag a box;
  every object fully inside is selected), **Select lasso** (trace a free-form loop; everything wholly
  inside is selected), or **Select text (PDF)**.
  Or **tap** a single object to select just that one. Selected objects get a dashed outline with
  handles:
  - **Drag inside the outline** to move them — drag onto a **different page** to move them there.
  - **Drag a corner handle** to resize (uniform scale).
  - **Drag the round knob poking out from the right edge** to rotate — shown only when the selection is *all
    strokes* (text and images have no rotation in the `.xopp` format, so they can't be rotated).
  - The floating action bar offers **Cut**, **Copy**, **Duplicate**, a **palette** to recolour (the
    same swatches, custom slot and Recent row as the pen's colour pop-up) and
    a **line-weight** menu to re-width the selection, **Delete**, and **Done** (deselect).
  - **Paste** appears in the bottom bar (when nothing is selected) and drops the copied objects onto
    the page you're viewing.

  All of these are undoable. (Selection is per page; two-finger pan still works.)
- **Text** — pick **Text** from the Tool pop-up and **tap** where you want a text box; a dialog
  takes the content from the keyboard and lets you style it: **font family** (Sans / Serif /
  Monospace), **bold**, **italic**, a **size** slider (6–96 pt), and a **colour** — the same picker
  the pen uses, so the custom slot and the Recent row are shared with it. Tapping
  an existing text box reopens it for editing with all of that prefilled from the box (clearing the
  text deletes it). Every property round-trips to and from desktop Xournal++ via the `.xopp`
  `<text>` element. (Underline isn't offered — the format can't store it.)
- **Image** — pick **Image** and **tap** where the image should go; the system picker opens, and the
  chosen picture is placed at that point (scaled to a sensible size).
- **LaTeX** — pick **LaTeX** and **tap** to place a math image; type the LaTeX source (e.g.
  `\frac{a}{b}`, `x^2`, `\sqrt{y}`, `\alpha`) and it's rendered as real math.
- **Undo / Redo** — the arrows in the top bar undo and redo edits, one gesture at a time (drawing,
  erasing, and adding/editing text/image/LaTeX are all undoable). They enable and disable as history
  allows; opening a file starts fresh history. History is **200 edits deep** — past that the oldest
  step is dropped, so the most recent edits always stay undoable without the stack growing forever.
- **Scroll** — drag with **two fingers** to move around the page stack, or pick the **Hand** tool
  from the Tool pop-up to pan with **one finger** (handy on a stylus). A quick **one-finger flick**
  keeps the pages **gliding** with momentum and coasts to a stop — the faster the flick, the much
  farther it carries — while a **two-finger** pan stops the instant you lift. Touch down again to halt
  a glide at once. With the
  Hand tool, a **double-tap** navigates: tap twice on the **left edge** to jump to the previous page,
  on the **right edge** for the next page, or in the **centre** to toggle **full-page view** (hides
  the top bar and side toolbar for a distraction-free canvas; double-tap the centre again to restore
  them). A
  PDF-style **scroll thumb**
  rides the **right edge** whenever the document is taller than the screen: **drag it** to page
  quickly through a long document (a **page-number bubble** shows where you are as you drag). A small
  **grip** bulges out of its centre so it's easy to grab. It sits faint while idle and brightens as you
  scroll; only the thumb itself grabs touches, so the rest of the page's right margin still takes ink.
- **Zoom** — **pinch** with **two fingers** anywhere on the canvas to zoom in or out; the point
  between your fingers stays put as the page grows or shrinks, and you can pan at the same time in
  the one gesture. The **%** button on the rail also opens a zoom pop-up with **−** / **+** buttons;
  tap the percentage to reset to 100%. Zooming wider than the screen lets you pan sideways. Zoom
  ranges from **25% to 1000%**, so you can work on fine detail; ink stays sharp at every level.
  PDF page backgrounds stay sharp too: past a certain zoom only the part of the page you can
  actually see is re-rendered, at full screen resolution, so PDF text is crisp all the way to 1000%.
  A freshly zoomed or panned area may look soft for a moment before the sharp version lands.
- **Background** — the **grid** button on the rail opens the **page-background** pop-up, which sets the
  paper ruling of the page in view: **Plain** (bare sheet), **Lined**, **Ruled** (lined with a red
  margin), **Graph**, or **Dotted**. The current style is check-marked; picking another re-rules the
  page immediately (an undoable edit) and round-trips via the `<background style>` attribute. On a
  **PDF** or image-backed page there's no solid sheet to re-rule, so the items are disabled.
- **Pages** — the document button on the rail opens the **page navigator**: it shows **Page N / M**
  with **◀ / ▶** to jump to the previous/next page, plus **Add page** (a blank page after the one in
  view — it keeps the current page's size and paper ruling, but a **PDF/image background is dropped to
  a plain white sheet** so the new page is genuinely blank, not a copy of the page underneath) and
  **Remove page** (the one in view; the last page is never removed). Add and remove are undoable.
  - **Pages per row** — the **1 / 2 / 3 / 4** chips zoom the canvas out to a **page overview**: pick
    2, 3 or 4 and the pages lay out side by side in a grid of that many columns (each fit to its
    column, rows top to bottom), instead of the usual single-page stack. Everything still works in
    the grid — you can draw, erase and select on whichever page you touch — so it doubles as a
    two-page spread for reading and a thumbnail overview for finding a page. The choice is
    remembered across launches; **1** returns to the single-page stack.
  - **Overview mode — View or Edit** — under the columns chips, the **View / Edit** chips decide what
    the grid does with a tap. **View** (the default) keeps the overview a pure reading-and-navigation
    layout: tapping a page with the **Hand** tool simply **jumps to that page**, and there is no
    selection, no selection tint, and no drag-to-reorder. **Edit** turns the page tooling on — tap to
    select, drag to reorder, and copy/paste/delete the selected pages (all described below). Leaving
    edit mode clears any selection, so the grid never keeps stale selection chrome on it. The chips
    are only available at 2 or more pages per row; drawing, erasing and selecting on a page work the
    same in either mode.
  - **Reorder pages in the overview** — while the grid is showing in **Edit** mode, **press and
    hold a page with your finger** until it dims: that lifts it. Drag to another page — the slot it
    would land in is outlined — and lift your finger to drop it there; the page moves to that
    position and the pages after it shift along. The move is a single undoable edit, and the new
    order is what gets written to the `.xopp`, so it round-trips to desktop Xournal++. The pen is
    never a candidate for the lift, so drawing on a grid page is unaffected; sliding your finger
    before the press registers pans as usual.
  - **Delete pages from the overview** — while the grid is showing in **Edit** mode, pick the **Hand** tool and **tap
    pages** to select them: each picked page is tinted and outlined, and tapping it again unpicks it.
    The Pages pop-up then grows three entries — **Copy N selected**, **Delete N selected**, which
    removes every picked page in **one undoable edit**, and **Clear selection**. A document always keeps at least one page, so
    selecting *every* page deletes nothing (that entry is disabled). The remaining pages keep their
    order and are what gets written to the `.xopp`. The selection is view-only state: it clears when
    you return to **1** page per row, and after any page add/remove/reorder (the indices have moved).
  - **Copy and paste pages** — **Copy N selected** puts the picked pages on a page clipboard (in
    document order) without changing anything yet. The Pages pop-up then offers **Paste N pages**,
    which inserts them in **one undoable edit** directly **after the last selected page** — or after
    the page in view when nothing is selected — and scrolls to the first pasted page. The copies carry
    everything the page holds: strokes with pressure, every layer and its name, page size, and the
    background (a ruled sheet, an imported image, or the same PDF page), so a pasted page is a true
    duplicate that round-trips to desktop Xournal++. The clipboard survives until the next copy, so
    one copy can be pasted repeatedly.
  - **Page size…** — the last row shows the page-in-view's size (a preset name like **A4**, or its
    dimensions) and opens a **Page size** dialog: pick a preset (**A4 / A5 / Letter / Legal**), or type
    a **custom** width and height in **mm / in / pt** (the unit toggle converts the fields), and **swap**
    width↔height for landscape. **Set** resizes that page (undoable); the dimensions round-trip via the
    `<page width= height=>` attributes to desktop Xournal++.
- **Settings** — the top-bar menu opens **Settings**, a list of sections — **Stylus**, **Editor**,
  **Toolbar**, **Navigation** and **Appearance**. Tap a section to open it as its own page; back returns to the list, and back from
  the list returns to the editor. Your choices persist across restarts. Under **Stylus**:
  - **Finger draws** — on by default; turn it **off** so fingers only pan/zoom and can never leave ink
    (best on a stylus tablet where a palm would otherwise draw).
  - **Hover preview** — show a ring where a hovering stylus will land.
  - **Barrel button** — what the stylus side-button does while held: **Erase** (default), **Select**,
    or **None**.
  - **Barrel double-click** — what a *rapid double-click* of that same button does, recognised only
    with the tip **off** the glass (so it never interrupts a stroke): **Undo** (default), **Redo**,
    **Toggle eraser**, **Toggle select**, **Toggle full page**, or **None**. The two toggles flip
    back to the previous tool when double-clicked again.
  - **Pressure sensitivity** — **Soft** (thickens with a light touch), **Linear**, or **Firm** (needs
    a harder press).
  - **Stroke precision** — how much of the pen's detail a stroke keeps: **Economy**, **Balanced**
    (default), **High**, or **Maximum**. Strokes are thinned to a sub-pixel error budget as they're
    drawn; raising the precision shrinks that budget, which draws visibly rounder curves on a large,
    high-density tablet at 100% zoom and below, at the cost of a bigger `.xopp`. Lowering it keeps
    files small. Existing strokes are unaffected — the setting applies to what you draw next.
    The budget is also capped in page units, so a stroke drawn zoomed out or in the multi-page
    overview stores the same detail as one drawn at 100% — zooming out never costs you precision.
  - **Shape recognition** — off by default; when on, a finished freehand pen stroke snaps to the
    primitive it resembles (see **Shape recognition** above).

  Under **Editor**:
  - **Snap to grid** — off by default; when on, the endpoints of a shape you drag out land on the
    page background's ruling instead of anywhere in between (see **Snapping** above).
  - **Snap rotation** — off by default; when on, rotating a selection steps in 15° increments.
  - **Default tool** — which tool is active when a document opens: **Pen** (default), **Highlighter**,
    **Eraser**, or **Hand (pan)**.

  Under **Toolbar**:
  - **Toolbar position** — which edge the tool rail is docked to: **Left** (default), **Right**,
    **Top**, or **Bottom**. Top/bottom lay the tool, colour, size, zoom, and page buttons out in a
    horizontal row along that edge; left/right keep the familiar vertical rail.
  - **Rail buttons** — the full list of rail positions (the seven tool slots plus Colour, Size,
    Style, Layers, Zoom, Background and Pages), each with a **switch** to hide it. To move one,
    **press and hold** its row and **drag** it up or down — the row lifts and the rest of the list
    shuffles under it as you go, so you can carry a button several places in one gesture. The rail
    draws them in this order, top-to-bottom (left-to-right when docked
    horizontally). Both the order and the hidden set are **remembered across app restarts**.

  Under **Navigation**:
  - **Momentum scrolling** — a slider setting how far a **one-finger** pan keeps gliding after you
    flick it. **0** turns momentum off (a released pan stops dead), **1.0** is normal (the default —
    a moderate flick coasts at about the speed you flicked), and higher values up to **10.0×** stretch
    every coast farther. Two-finger pans never glide, whatever this is set to.
  - **Momentum curve** — picks how sharply a *faster* flick coasts *farther*: **Linear** (even),
    **Quadratic** (the default — coast grows with the square of flick speed), **Cubic**, or
    **Exponential** (rewards fast swipes the most, so a tiny flick barely drifts while a hard swipe
    flies many pages). All four meet at the same moderate-flick reference, so this only changes how
    small flicks fall off and fast ones take off — the slider above still sets the overall strength.
  - **Panning sensitivity** — a slider setting how far the canvas moves per unit of pan travel. **1.0**
    is one-to-one (the default — the page tracks your finger exactly), values **below 1** pan slower
    than your finger, values up to **4.0×** pan faster, and **0** turns panning off entirely. The gain
    also scales the fling, so a released pan coasts at the same visual rate it was moving.

  Under **Appearance**:
  - **Theme** — **System** (the default — follows the device's light/dark setting), **Light**, or
    **Dark**. The choice repaints the whole app from one Material 3 scheme: the top bar, the tool
    rail and its swatch rings, the settings pages, and the canvas backdrop, selection and guide
    colours. Page and ink colours are document data and never change with the theme.
- **Export PDF** — the menu's **Export PDF** flattens the whole document to a PDF: each page is
  drawn at its true size with its background (a PDF page or a ruled sheet) and every stroke and
  element merged on top, then written to the location you pick. When a page came from an **imported
  PDF**, its original page is **kept as vector content** and your annotations are laid over it as
  vectors too — so re-exporting an unchanged PDF stays about its original size and sharpness instead
  of ballooning from a rasterised copy. Use this to share an annotated copy; **Save** keeps the
  editable `.xopp`.
- **Audio (record & replay)** — the rail's **Audio** slot records the microphone while you write,
  and every stroke you draw is tagged with the moment in that recording it was started. Tap
  **Record** to start (Android asks for microphone permission the first time), and **Stop
  recording** to finish. Then pick the **Play object** tool from the rail and **tap any stroke** to
  hear the audio from the instant that stroke was drawn — the same `fn`/`ts` stroke tagging desktop
  Xournal++ uses, so recordings made there replay here and vice versa. **Stop playback** in the same
  pop-up silences it.

  Audio is **not** stored inside the `.xopp` — it lives in a `.wav` file *beside* it, exactly as on
  the desktop. Android only grants an app access to the one file you picked, not its folder, so the
  first time you record, use **Choose audio folder…** in the Audio pop-up and pick the folder your
  `.xopp` files live in. Xopp then writes new recordings there when you save, and loads a document's
  recordings from there when you open it. Until you choose a folder, recording and playback still
  work for the session, but the `.wav` never leaves the app — so a file you take back to the desktop
  won't have its audio.

- **Save** — the menu's **Save** writes the whole document back out to a `.xopp` file, preserving
  every page, layer, background, and element — strokes plus the text, images, and LaTeX images you
  authored on-device. Save writes in whichever **format you last chose in Save As…** (see below):
  it starts as **Original**, and once you Save As **Zipped**, every later Save stays Zipped until
  you switch back. Opening a file adopts the format it was stored in.
  **Save writes straight back to the file the tab came from** — no picker — whether that file is
  on local storage or a mounted remote share; you're only asked for a location when the tab has no
  file yet (or the grant on it has lapsed). The document is encoded locally and then pushed across
  in one pass behind a "Saving…" note, so a slow or broken link can never leave a half-written
  `.xopp` on the far end.

- **Save As…** — the menu's **Save As…** opens a dialog to name the file and pick its format:
  - **Original (gzip)** — the standard Xournal++ `.xopp` (gzip-compressed XML). For a PDF-backed
    document the PDF stays **linked by location** (its path/URI), so the `.xopp` is small and
    reopening it reloads that PDF from where it lives — the interchange-safe default.
  - **Zipped (single file)** — one self-contained `.xopp` with the **PDF embedded inside** it, so
    the document is fully portable and moves as a single file. It reopens with its background intact
    in Xopp itself (the PDF travels in the same file) as well as on desktop Xournal++.
    - **Note — targeting release Xournal++ on Arch Linux.** The current released desktop Xournal++
      (1.3.5) has a bug in its ZIP reader that rejects a *correctly* labelled archive, so Xopp
      deliberately writes a slightly non-standard internal marker to open on that release. This is a
      temporary workaround; it will be reverted to the standard once upstream fixes the bug.

The file on disk is the only source of truth — there's no cloud, account, or custom format.

The file on disk is the only source of truth — there's no cloud, account, or custom format.

## Project layout

The authoritative layout lives in [`docs/architecture.md`](docs/architecture.md). In short:

```
app/src/main/java/com/xopp/android/
  format/      # .xopp read/write: model, colour codec, gzip, dependency-free XML layer
  render/      # stylus canvas, page layout/rendering, PDF import & export
  tabs/        # multiple open documents + the session cache that restores them on launch
  panes/       # the one-or-two editing panes of split view, each with its own tabs and canvas
  ui/          # Compose Material 3 editor screen, dockable toolbar rail pop-ups, settings, theme
  MainActivity.kt
app/src/test/  # JVM unit tests for the format and render layers
Dockerfile, compose.yaml, scripts/build.sh   # containerized build
```
