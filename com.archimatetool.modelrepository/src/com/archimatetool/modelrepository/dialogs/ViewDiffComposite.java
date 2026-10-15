/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.modelrepository.dialogs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.Polyline;
import org.eclipse.draw2d.PolylineConnection;
import org.eclipse.draw2d.RectangleFigure;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.EditPartFactory;
import org.eclipse.gef.GraphicalEditPart;
import org.eclipse.gef.GraphicalViewer;
import org.eclipse.gef.LayerConstants;
import org.eclipse.gef.editparts.ScalableFreeformRootEditPart;
import org.eclipse.gef.editparts.ZoomManager;
import org.eclipse.gef.ui.parts.ScrollingGraphicalViewer;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerEditor;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationStrategy;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ComboBoxViewerCellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TableViewerEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.editor.diagram.DiagramEditorFactoryExtensionHandler;
import com.archimatetool.editor.diagram.IDiagramEditorFactory;
import com.archimatetool.editor.diagram.editparts.ArchimateDiagramEditPartFactory;
import com.archimatetool.editor.diagram.sketch.editparts.SketchEditPartFactory;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.ISketchModel;
import com.archimatetool.modelrepository.dialogs.DiagramVisualApplier.Result;
import com.archimatetool.modelrepository.dialogs.DiagramVisualApplier.VisualDecision;
import com.archimatetool.modelrepository.dialogs.DiagramVisualChange.AbsoluteBounds;
import com.archimatetool.modelrepository.dialogs.DiagramVisualChange.AbsolutePoint;
import com.archimatetool.modelrepository.dialogs.DiagramVisualChange.Kind;

/**
 * Live Archi canvas of the result diagram with overlay marks for unresolved visual differences vs REMOTE.
 */
public class ViewDiffComposite extends Composite {

    private SashForm sash;
    private TableViewer changeViewer;
    private Button btnApply;
    private Button btnCancel;
    private Button chkShowAll;
    private Consumer<Map<String, VisualDecision>> applyHandler;
    private Composite viewerParent;
    private GraphicalViewer graphicalViewer;
    private IFigure overlayLayer;
    private List<DiagramVisualChange> allChanges = List.of();
    private List<DiagramVisualChange> changes = List.of();
    private Set<String> conflictVisualIds = Set.of();
    private final Map<DiagramVisualChange, List<IFigure>> figuresByChange = new HashMap<>();
    private final Map<String, VisualDecision> decisions = new HashMap<>();
    private IDiagramModel remoteDiagram;
    private IDiagramModel localDiagram;
    private IArchimateModel mergedBaseModel;
    private IDiagramModel resultDiagram;
    private IArchimateModel resultModel;
    private double lastZoom = 1.0;
    private Point lastViewLocation;
    private Color addedColor;
    private Color deletedColor;
    private Color movedColor;
    private Color changedColor;

    public ViewDiffComposite(Composite parent, int style) {
        super(parent, style);
        setLayoutData(new GridData(GridData.FILL_BOTH));
        setLayout(GridLayoutFactory.fillDefaults().create());
        addedColor = new Color(getDisplay(), 0, 140, 0);
        deletedColor = new Color(getDisplay(), 180, 0, 0);
        movedColor = new Color(getDisplay(), 0, 80, 180);
        changedColor = new Color(getDisplay(), 180, 140, 0);
        addDisposeListener(e -> {
            disposeViewer();
            addedColor.dispose();
            deletedColor.dispose();
            movedColor.dispose();
            changedColor.dispose();
        });
    }

    public void setDiagramModel(IDiagramModel dm) {
        ensureUi();
        localDiagram = dm;
        changes = List.of();
        decisions.clear();
        resultModel = null;
        resultDiagram = dm;
        changeViewer.setInput(changes);
        updateApplyEnabled();
        setCanvas(dm, false);
    }

    /**
     * @param dm1               REMOTE
     * @param dm2               LOCAL (overlay source)
     * @param conflictVisualIds REAL-conflict visual object ids on this diagram
     * @param mergedBase        auto-merge of LOCAL+REMOTE except REAL conflicts; required
     */
    public void setDiagramModels(IDiagramModel dm1, IDiagramModel dm2, Set<String> conflictVisualIds,
            IArchimateModel mergedBase) {
        if (mergedBase == null) {
            throw new IllegalArgumentException("mergedBase is required"); //$NON-NLS-1$
        }
        ensureUi();
        remoteDiagram = dm1;
        localDiagram = dm2;
        mergedBaseModel = mergedBase;
        this.conflictVisualIds = conflictVisualIds != null ? Set.copyOf(conflictVisualIds) : Set.of();
        allChanges = buildAllChanges(dm1, dm2, this.conflictVisualIds);
        decisions.clear();
        if (chkShowAll != null && !chkShowAll.isDisposed()) {
            chkShowAll.setSelection(false);
        }
        refreshVisibleRows(true);
    }

    public void setApplyHandler(Consumer<Map<String, VisualDecision>> applyHandler) {
        this.applyHandler = applyHandler;
    }

    public void clear() {
        disposeViewer();
        allChanges = List.of();
        changes = List.of();
        conflictVisualIds = Set.of();
        decisions.clear();
        remoteDiagram = null;
        localDiagram = null;
        mergedBaseModel = null;
        resultDiagram = null;
        resultModel = null;
        if (changeViewer != null && !changeViewer.getTable().isDisposed()) {
            changeViewer.setInput(changes);
        }
        if (sash != null && !sash.isDisposed()) {
            sash.dispose();
        }
        sash = null;
        changeViewer = null;
        btnApply = null;
        btnCancel = null;
        chkShowAll = null;
        viewerParent = null;
        overlayLayer = null;
    }

    private void ensureUi() {
        if (sash != null && !sash.isDisposed()) {
            return;
        }

        sash = new SashForm(this, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite left = new Composite(sash, SWT.NONE);
        left.setLayout(new GridLayout(1, false));

        chkShowAll = new Button(left, SWT.CHECK);
        chkShowAll.setText("show all changes"); //$NON-NLS-1$
        chkShowAll.setSelection(false);
        chkShowAll.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        chkShowAll.addListener(SWT.Selection, e -> refreshVisibleRows(true));

        Composite listPanel = new Composite(left, SWT.NONE);
        listPanel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TableColumnLayout tableLayout = new TableColumnLayout();
        listPanel.setLayout(tableLayout);

        changeViewer = new TableViewer(listPanel, SWT.FULL_SELECTION | SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        changeViewer.getTable().setHeaderVisible(true);
        changeViewer.getTable().setLinesVisible(true);
        changeViewer.setContentProvider(ArrayContentProvider.getInstance());

        ColumnViewerEditorActivationStrategy activation = new ColumnViewerEditorActivationStrategy(changeViewer) {
            @Override
            protected boolean isEditorActivationEvent(ColumnViewerEditorActivationEvent event) {
                return event.eventType == ColumnViewerEditorActivationEvent.TRAVERSAL
                        || event.eventType == ColumnViewerEditorActivationEvent.MOUSE_CLICK_SELECTION
                        || event.eventType == ColumnViewerEditorActivationEvent.PROGRAMMATIC;
            }
        };
        TableViewerEditor.create(changeViewer, activation,
                ColumnViewerEditor.TABBING_HORIZONTAL | ColumnViewerEditor.TABBING_MOVE_TO_ROW_NEIGHBOR
                        | ColumnViewerEditor.KEYBOARD_ACTIVATION);

        TableViewerColumn kindCol = new TableViewerColumn(changeViewer, SWT.NONE);
        kindCol.getColumn().setText("Kind"); //$NON-NLS-1$
        tableLayout.setColumnData(kindCol.getColumn(), new ColumnWeightData(18, true));
        kindCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof DiagramVisualChange c ? kindLabel(c.kind()) : ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn typeCol = new TableViewerColumn(changeViewer, SWT.NONE);
        typeCol.getColumn().setText("Type"); //$NON-NLS-1$
        tableLayout.setColumnData(typeCol.getColumn(), new ColumnWeightData(22, true));
        typeCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof DiagramVisualChange c ? typeLabel(c) : ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn nameCol = new TableViewerColumn(changeViewer, SWT.NONE);
        nameCol.getColumn().setText("Object"); //$NON-NLS-1$
        tableLayout.setColumnData(nameCol.getColumn(), new ColumnWeightData(36, true));
        nameCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof DiagramVisualChange c ? c.label() : ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn decisionCol = new TableViewerColumn(changeViewer, SWT.NONE);
        decisionCol.getColumn().setText("Decision"); //$NON-NLS-1$
        tableLayout.setColumnData(decisionCol.getColumn(), new ColumnWeightData(24, true));
        decisionCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof DiagramVisualChange c) {
                    return decisionLabel(decisionOf(c));
                }
                return ""; //$NON-NLS-1$
            }
        });
        decisionCol.setEditingSupport(new DecisionEditingSupport());

        changeViewer.addSelectionChangedListener(event -> {
            Object selected = ((IStructuredSelection) event.getSelection()).getFirstElement();
            if (selected instanceof DiagramVisualChange change) {
                reveal(change);
            }
        });

        Composite buttons = new Composite(left, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        buttons.setLayout(new GridLayout(2, true));
        btnApply = new Button(buttons, SWT.PUSH);
        btnApply.setText("Apply"); //$NON-NLS-1$
        btnApply.setEnabled(false);
        btnApply.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        btnApply.addListener(SWT.Selection, e -> applyAndClose());
        btnCancel = new Button(buttons, SWT.PUSH);
        btnCancel.setText("Cancel"); //$NON-NLS-1$
        btnCancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        btnCancel.addListener(SWT.Selection, e -> closeShell());

        Composite right = new Composite(sash, SWT.NONE);
        right.setLayout(new GridLayout(1, false));
        right.setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        right.setBackgroundMode(SWT.INHERIT_FORCE);

        Composite legend = new Composite(right, SWT.NONE);
        legend.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        legend.setLayout(new GridLayout(8, false));
        addLegendItem(legend, addedColor, "Added"); //$NON-NLS-1$
        addLegendItem(legend, deletedColor, "Deleted"); //$NON-NLS-1$
        addLegendItem(legend, movedColor, "Moved"); //$NON-NLS-1$
        addLegendItem(legend, changedColor, "Changed"); //$NON-NLS-1$

        viewerParent = new Composite(right, SWT.BORDER);
        viewerParent.setLayoutData(new GridData(GridData.FILL_BOTH));
        viewerParent.setLayout(new FillLayout());

        sash.setWeights(new int[] { 28, 72 });
        layout();
    }

    private void refreshVisibleRows(boolean replay) {
        boolean showAll = chkShowAll != null && !chkShowAll.isDisposed() && chkShowAll.getSelection();
        changes = showAll ? List.copyOf(allChanges) : visibleConflictChanges();
        if (changeViewer != null && !changeViewer.getTable().isDisposed()) {
            changeViewer.setInput(changes);
        }
        updateApplyEnabled();
        if (replay) {
            replayResultAndShow(true);
        }
    }

    private List<DiagramVisualChange> visibleConflictChanges() {
        List<DiagramVisualChange> filtered = new ArrayList<>();
        for (DiagramVisualChange change : allChanges) {
            if (conflictVisualIds.contains(change.id())) {
                filtered.add(change);
            }
        }
        return filtered;
    }

    private List<DiagramVisualChange> buildAllChanges(IDiagramModel remote, IDiagramModel local, Set<String> ids) {
        List<DiagramVisualChange> built = new ArrayList<>(DiagramVisualDiffBuilder.build(remote, local));
        if (ids == null || ids.isEmpty()) {
            return built;
        }
        Map<String, DiagramVisualChange> byId = new LinkedHashMap<>();
        for (DiagramVisualChange change : built) {
            byId.put(change.id(), change);
        }
        Map<String, EObject> localIndex = DiagramVisualDiffBuilder.index(local);
        Map<String, EObject> remoteIndex = DiagramVisualDiffBuilder.index(remote);
        for (String id : ids) {
            if (id == null || id.isEmpty() || byId.containsKey(id)) {
                continue;
            }
            EObject localObj = localIndex.get(id);
            EObject remoteObj = remoteIndex.get(id);
            built.add(new DiagramVisualChange(Kind.CHANGED, id, MergeLabels.labelOf(localObj != null ? localObj : remoteObj),
                    localObj, remoteObj, false, null, null, null));
        }
        return built;
    }

    private static String typeLabel(DiagramVisualChange change) {
        return MergeLabels.typeLabel(change.localObject() != null ? change.localObject() : change.remoteObject());
    }

    private void applyAndClose() {
        if (applyHandler != null) {
            applyHandler.accept(new LinkedHashMap<>(decisions));
        }
        closeShell();
    }

    private void closeShell() {
        Shell shell = getShell();
        if (shell != null && !shell.isDisposed()) {
            shell.close();
        }
    }

    private void updateApplyEnabled() {
        if (btnApply == null || btnApply.isDisposed()) {
            return;
        }
        boolean allResolved = !changes.isEmpty();
        if (allResolved) {
            for (DiagramVisualChange change : changes) {
                if (decisionOf(change) == VisualDecision.UNRESOLVED) {
                    allResolved = false;
                    break;
                }
            }
        }
        btnApply.setEnabled(allResolved);
    }

    private void addLegendItem(Composite parent, Color color, String text) {
        org.eclipse.swt.widgets.Label swatch = new org.eclipse.swt.widgets.Label(parent, SWT.NONE);
        swatch.setText("  "); //$NON-NLS-1$
        swatch.setBackground(color);
        GridData swatchData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        swatchData.widthHint = 16;
        swatchData.heightHint = 12;
        swatch.setLayoutData(swatchData);
        org.eclipse.swt.widgets.Label caption = new org.eclipse.swt.widgets.Label(parent, SWT.NONE);
        caption.setText(text);
    }

    private void replayResultAndShow(boolean withOverlays) {
        rememberViewport();
        Result result = DiagramVisualApplier.replay(mergedBaseModel, localDiagram.getId(), allChanges, decisions);
        resultModel = result.model();
        resultDiagram = result.diagram();
        setCanvas(resultDiagram, withOverlays);
    }

    private ScrollingGraphicalViewer createScalableViewer(IDiagramModel model, Composite parent) {
        EditPartFactory editPartFactory = null;

        if (model instanceof IArchimateDiagramModel) {
            editPartFactory = new ArchimateDiagramEditPartFactory();
        }
        else if (model instanceof ISketchModel) {
            editPartFactory = new SketchEditPartFactory();
        }
        else {
            IDiagramEditorFactory factory = DiagramEditorFactoryExtensionHandler.INSTANCE.getFactory(model);
            if (factory != null) {
                editPartFactory = factory.createEditPartFactory();
            }
        }

        if (editPartFactory == null) {
            throw new RuntimeException("Unsupported model type"); //$NON-NLS-1$
        }

        ScrollingGraphicalViewer viewer = new ScrollingGraphicalViewer();
        viewer.setEditPartFactory(editPartFactory);
        viewer.setRootEditPart(new ScalableFreeformRootEditPart());
        viewer.createControl(parent);

        viewer.setContents(model);
        viewer.flush();
        installCanvasInteraction((FigureCanvas) viewer.getControl(), viewer);

        return viewer;
    }

    private void installCanvasInteraction(FigureCanvas canvas, GraphicalViewer viewer) {
        final boolean[] panning = { false };
        final Point startCursor = new Point();
        final Point startViewLocation = new Point();
        final Cursor panCursor = new Cursor(canvas.getDisplay(), SWT.CURSOR_SIZEALL);

        canvas.addListener(SWT.MouseWheel, e -> {
            if (canvas.isDisposed()) {
                return;
            }
            if ((e.stateMask & SWT.MOD1) != 0) {
                ZoomManager zm = zoomManager(viewer);
                if (zm == null) {
                    return;
                }
                if (e.count > 0) {
                    zm.zoomIn();
                }
                else {
                    zm.zoomOut();
                }
                lastZoom = zm.getZoom();
                viewer.flush();
                e.doit = false;
                return;
            }
            if ((e.stateMask & SWT.SHIFT) != 0) {
                var range = canvas.getViewport().getHorizontalRangeModel();
                range.setValue(range.getValue() - e.count * 16);
                e.doit = false;
            }
        });

        canvas.addListener(SWT.MouseDown, e -> {
            if (canvas.isDisposed() || e.button != 2) {
                return;
            }
            panning[0] = true;
            startCursor.setLocation(e.x, e.y);
            startViewLocation.setLocation(canvas.getViewport().getViewLocation());
            canvas.setCursor(panCursor);
            e.doit = false;
        });

        canvas.addListener(SWT.MouseMove, e -> {
            if (!panning[0] || canvas.isDisposed()) {
                return;
            }
            canvas.getViewport().setViewLocation(
                    startViewLocation.x + (startCursor.x - e.x),
                    startViewLocation.y + (startCursor.y - e.y));
        });

        Runnable stopPan = () -> {
            if (!panning[0]) {
                return;
            }
            panning[0] = false;
            if (!canvas.isDisposed()) {
                canvas.setCursor(null);
            }
        };
        canvas.addListener(SWT.MouseUp, e -> {
            if (e.button == 2) {
                stopPan.run();
            }
        });
        canvas.addListener(SWT.MouseExit, e -> stopPan.run());
        canvas.addDisposeListener(e -> panCursor.dispose());
    }

    private void setCanvas(IDiagramModel diagram, boolean withOverlays) {
        disposeViewer();
        if (viewerParent == null || viewerParent.isDisposed() || diagram == null) {
            return;
        }
        try {
            graphicalViewer = createScalableViewer(diagram, viewerParent);
            viewerParent.layout();
            restoreViewport();
            if (withOverlays) {
                Display.getDefault().asyncExec(() -> {
                    if (isDisposed() || graphicalViewer == null) {
                        return;
                    }
                    applyOverlays();
                });
            }
        }
        catch (RuntimeException ex) {
            graphicalViewer = null;
        }
    }

    private void rememberViewport() {
        ZoomManager zm = zoomManager(graphicalViewer);
        if (zm != null) {
            lastZoom = zm.getZoom();
        }
        if (graphicalViewer != null && graphicalViewer.getControl() instanceof FigureCanvas canvas
                && !canvas.isDisposed()) {
            lastViewLocation = canvas.getViewport().getViewLocation().getCopy();
        }
    }

    private void restoreViewport() {
        ZoomManager zm = zoomManager(graphicalViewer);
        if (zm != null) {
            zm.setZoom(lastZoom);
        }
        if (lastViewLocation != null && graphicalViewer != null
                && graphicalViewer.getControl() instanceof FigureCanvas canvas && !canvas.isDisposed()) {
            canvas.getViewport().setViewLocation(lastViewLocation);
        }
    }

    private ZoomManager zoomManager(GraphicalViewer viewer) {
        if (viewer == null) {
            return null;
        }
        if (viewer.getRootEditPart() instanceof ScalableFreeformRootEditPart root) {
            return root.getZoomManager();
        }
        return null;
    }

    private void applyOverlays() {
        clearOverlayFigures();
        overlayLayer = feedbackLayer();
        if (overlayLayer == null) {
            return;
        }
        overlayLayer.setVisible(true);
        graphicalViewer.flush();
        for (DiagramVisualChange change : changes) {
            if (decisionOf(change) != VisualDecision.UNRESOLVED) {
                continue;
            }
            List<IFigure> figures = new ArrayList<>();
            if (change.isConnection()) {
                addConnectionOverlays(change, figures);
            }
            else {
                addNodeOverlays(change, figures);
            }
            figuresByChange.put(change, figures);
        }
        overlayLayer.repaint();
    }

    private void addNodeOverlays(DiagramVisualChange change, List<IFigure> figures) {
        Color color = colorFor(change.kind());
        boolean showGhost = change.kind() == Kind.DELETED || change.boundsChanged();
        if (showGhost && change.remoteBounds() != null) {
            addGhostRect(change.remoteBounds(), color, change.label(),
                    change.kind() == Kind.DELETED ? "−" : "", figures); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (change.kind() != Kind.DELETED) {
            Rectangle bounds = figureBounds(resultObject(change));
            if (bounds != null) {
                String mark = change.kind() == Kind.ADDED ? "+" : ""; //$NON-NLS-1$ //$NON-NLS-2$
                figures.add(addOutlineRect(bounds, color, mark, false));
            }
        }
    }

    private void addConnectionOverlays(DiagramVisualChange change, List<IFigure> figures) {
        Color color = colorFor(change.kind());
        if (change.kind() == Kind.DELETED) {
            AbsolutePoint src = change.remoteSourcePoint();
            AbsolutePoint tgt = change.remoteTargetPoint();
            if (src != null && tgt != null) {
                figures.add(addLine(modelPointToLayer(new Point(src.x(), src.y())),
                        modelPointToLayer(new Point(tgt.x(), tgt.y())), color, true));
            }
            return;
        }
        PointList localPoints = connectionPoints(resultObject(change));
        if (localPoints != null && localPoints.size() >= 2) {
            figures.add(addPolyline(localPoints, color, false));
        }
    }

    private void addGhostRect(AbsoluteBounds abs, Color color, String name, String mark, List<IFigure> figures) {
        Rectangle bounds = modelRectToLayer(new Rectangle(abs.x(), abs.y(), abs.width(), abs.height()));
        RectangleFigure rf = new RectangleFigure();
        rf.setFill(true);
        rf.setAlpha(50);
        rf.setOutline(true);
        rf.setLineStyle(Graphics.LINE_DASH);
        rf.setLineWidth(2);
        rf.setForegroundColor(color);
        rf.setBackgroundColor(color);
        rf.setBounds(bounds.getExpanded(2, 2));
        overlayLayer.add(rf);
        figures.add(rf);

        Label caption = new Label((mark == null || mark.isEmpty() ? "" : mark + " ") + name); //$NON-NLS-1$ //$NON-NLS-2$
        caption.setForegroundColor(color);
        caption.setBounds(new Rectangle(rf.getBounds().x, rf.getBounds().y - 16, Math.max(80, rf.getBounds().width), 16));
        overlayLayer.add(caption);
        figures.add(caption);
    }

    private IFigure addOutlineRect(Rectangle bounds, Color color, String mark, boolean dashed) {
        RectangleFigure rf = new RectangleFigure();
        rf.setFill(false);
        rf.setOutline(true);
        rf.setLineWidth(2);
        if (dashed) {
            rf.setLineStyle(Graphics.LINE_DASH);
        }
        rf.setForegroundColor(color);
        rf.setBounds(bounds.getCopy().expand(3, 3));
        overlayLayer.add(rf);
        if (mark != null && !mark.isEmpty()) {
            Label caption = new Label(mark);
            caption.setForegroundColor(color);
            caption.setBounds(new Rectangle(rf.getBounds().right() - 12, rf.getBounds().y, 14, 14));
            overlayLayer.add(caption);
        }
        return rf;
    }

    private IFigure addLine(Point p1, Point p2, Color color, boolean dashed) {
        PointList points = new PointList();
        points.addPoint(p1);
        points.addPoint(p2);
        return addPolyline(points, color, dashed);
    }

    private IFigure addPolyline(PointList points, Color color, boolean dashed) {
        Polyline line = new Polyline();
        line.setPoints(points);
        line.setForegroundColor(color);
        line.setLineWidth(3);
        if (dashed) {
            line.setLineStyle(Graphics.LINE_DASH);
        }
        overlayLayer.add(line);
        return line;
    }

    private EObject resultObject(DiagramVisualChange change) {
        return DiagramVisualApplier.findInDiagram(resultDiagram, change.id());
    }

    private VisualDecision decisionOf(DiagramVisualChange change) {
        return decisions.getOrDefault(change.id(), VisualDecision.UNRESOLVED);
    }

    private Rectangle figureBounds(EObject model) {
        GraphicalEditPart part = editPart(model);
        if (part == null || part.getFigure() == null) {
            return null;
        }
        IFigure figure = part.getFigure();
        Rectangle bounds = figure.getBounds().getCopy();
        figure.translateToAbsolute(bounds);
        overlayLayer.translateToRelative(bounds);
        return bounds;
    }

    private PointList connectionPoints(EObject model) {
        GraphicalEditPart part = editPart(model);
        if (part == null || part.getFigure() == null) {
            return null;
        }
        IFigure figure = part.getFigure();
        if (!(figure instanceof PolylineConnection poly)) {
            return figureBoundsAsLine(figure);
        }
        PointList src = poly.getPoints();
        if (src == null || src.size() < 2) {
            return figureBoundsAsLine(figure);
        }
        PointList copy = new PointList(src.size());
        for (int i = 0; i < src.size(); i++) {
            Point p = src.getPoint(i).getCopy();
            figure.translateToAbsolute(p);
            overlayLayer.translateToRelative(p);
            copy.addPoint(p);
        }
        return copy;
    }

    private PointList figureBoundsAsLine(IFigure figure) {
        Rectangle bounds = figure.getBounds().getCopy();
        figure.translateToAbsolute(bounds);
        overlayLayer.translateToRelative(bounds);
        PointList points = new PointList();
        points.addPoint(bounds.getTopLeft());
        points.addPoint(bounds.getBottomRight());
        return points;
    }

    private Point modelPointToLayer(Point diagramPoint) {
        IFigure contents = contentsFigure();
        Point p = diagramPoint.getCopy();
        if (contents != null) {
            contents.translateToAbsolute(p);
        }
        if (overlayLayer != null) {
            overlayLayer.translateToRelative(p);
        }
        return p;
    }

    private Rectangle modelRectToLayer(Rectangle diagramRect) {
        IFigure contents = contentsFigure();
        Rectangle r = diagramRect.getCopy();
        if (contents != null) {
            contents.translateToAbsolute(r);
        }
        if (overlayLayer != null) {
            overlayLayer.translateToRelative(r);
        }
        return r;
    }

    private IFigure contentsFigure() {
        if (graphicalViewer == null || graphicalViewer.getContents() == null) {
            return null;
        }
        Object contents = graphicalViewer.getContents();
        if (contents instanceof GraphicalEditPart gep) {
            return gep.getFigure();
        }
        return null;
    }

    private GraphicalEditPart editPart(EObject model) {
        if (graphicalViewer == null || model == null) {
            return null;
        }
        Object part = graphicalViewer.getEditPartRegistry().get(model);
        return part instanceof GraphicalEditPart gep ? gep : null;
    }

    private IFigure feedbackLayer() {
        if (graphicalViewer == null) {
            return null;
        }
        if (graphicalViewer.getRootEditPart() instanceof ScalableFreeformRootEditPart root) {
            return root.getLayer(LayerConstants.SCALED_FEEDBACK_LAYER);
        }
        return null;
    }

    private void reveal(DiagramVisualChange change) {
        emphasize(change);
        GraphicalEditPart part = editPart(resultObject(change));
        if (part != null && graphicalViewer != null) {
            graphicalViewer.reveal(part);
            return;
        }
        List<IFigure> figs = figuresByChange.get(change);
        if (graphicalViewer == null || figs == null || figs.isEmpty()
                || !(graphicalViewer.getControl() instanceof FigureCanvas canvas)) {
            return;
        }
        Rectangle b = figs.get(0).getBounds().getCopy();
        canvas.getViewport().setViewLocation(Math.max(0, b.x - 40), Math.max(0, b.y - 40));
    }

    private void emphasize(DiagramVisualChange selected) {
        for (Map.Entry<DiagramVisualChange, List<IFigure>> e : figuresByChange.entrySet()) {
            int width = e.getKey() == selected ? 4 : 2;
            for (IFigure f : e.getValue()) {
                if (f instanceof RectangleFigure rf) {
                    rf.setLineWidth(width);
                }
                else if (f instanceof Polyline pl) {
                    pl.setLineWidth(width + 1);
                }
            }
        }
        if (overlayLayer != null) {
            overlayLayer.repaint();
        }
    }

    private Color colorFor(Kind kind) {
        return switch (kind) {
            case ADDED -> addedColor;
            case DELETED -> deletedColor;
            case MOVED -> movedColor;
            case CHANGED -> changedColor;
        };
    }

    private static String kindLabel(Kind kind) {
        return switch (kind) {
            case ADDED -> "Added"; //$NON-NLS-1$
            case DELETED -> "Deleted"; //$NON-NLS-1$
            case MOVED -> "Moved"; //$NON-NLS-1$
            case CHANGED -> "Changed"; //$NON-NLS-1$
        };
    }

    private static String decisionLabel(VisualDecision decision) {
        return switch (decision) {
            case UNRESOLVED -> ""; //$NON-NLS-1$
            case LOCAL -> "Local"; //$NON-NLS-1$
            case REMOTE -> "Remote"; //$NON-NLS-1$
        };
    }

    private void clearOverlayFigures() {
        if (overlayLayer != null) {
            for (List<IFigure> figs : figuresByChange.values()) {
                for (IFigure f : figs) {
                    if (f.getParent() != null) {
                        f.getParent().remove(f);
                    }
                }
            }
            List<?> children = new ArrayList<>(overlayLayer.getChildren());
            for (Object child : children) {
                overlayLayer.remove((IFigure) child);
            }
        }
        figuresByChange.clear();
    }

    private void disposeViewer() {
        clearOverlayFigures();
        overlayLayer = null;
        if (graphicalViewer != null) {
            Control control = graphicalViewer.getControl();
            if (control != null && !control.isDisposed()) {
                control.dispose();
            }
            graphicalViewer = null;
        }
    }

    private class DecisionEditingSupport extends EditingSupport {
        private final ComboBoxViewerCellEditor editor;

        DecisionEditingSupport() {
            super(changeViewer);
            editor = new ComboBoxViewerCellEditor(changeViewer.getTable(), SWT.READ_ONLY);
            editor.setContentProvider(ArrayContentProvider.getInstance());
            editor.setLabelProvider(new LabelProvider() {
                @Override
                public String getText(Object element) {
                    return element instanceof VisualDecision d ? decisionLabel(d) : ""; //$NON-NLS-1$
                }
            });
            editor.setInput(VisualDecision.values());
        }

        @Override
        protected org.eclipse.jface.viewers.CellEditor getCellEditor(Object element) {
            return editor;
        }

        @Override
        protected boolean canEdit(Object element) {
            return element instanceof DiagramVisualChange;
        }

        @Override
        protected Object getValue(Object element) {
            return element instanceof DiagramVisualChange c ? decisionOf(c) : VisualDecision.UNRESOLVED;
        }

        @Override
        protected void setValue(Object element, Object value) {
            if (!(element instanceof DiagramVisualChange change)) {
                return;
            }
            VisualDecision next = value instanceof VisualDecision d ? d : VisualDecision.UNRESOLVED;
            List<DiagramVisualChange> targets = selectedChanges();
            if (targets.isEmpty() || !targets.contains(change)) {
                targets = List.of(change);
            }
            boolean any = false;
            for (DiagramVisualChange target : targets) {
                if (decisionOf(target) != next) {
                    decisions.put(target.id(), next);
                    any = true;
                }
            }
            if (!any) {
                return;
            }
            changeViewer.refresh();
            updateApplyEnabled();
            replayResultAndShow(true);
        }

        private List<DiagramVisualChange> selectedChanges() {
            List<DiagramVisualChange> selected = new ArrayList<>();
            for (Object el : changeViewer.getStructuredSelection().toList()) {
                if (el instanceof DiagramVisualChange c) {
                    selected.add(c);
                }
            }
            return selected;
        }
    }
}
