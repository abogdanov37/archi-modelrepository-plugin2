/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.modelrepository.dialogs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.modelrepository.merge.MergeUtils;

/**
 * Replays Local and Remote visual decisions onto a working copy of the auto-merged (except conflicts) model.
 */
public final class DiagramVisualApplier {

    public enum VisualDecision {
        UNRESOLVED, LOCAL, REMOTE
    }

    public record Result(IArchimateModel model, IDiagramModel diagram) {
    }

    private DiagramVisualApplier() {
    }

    /**
     * Copies {@code mergedBaseModel} and applies every Local/Remote visual decision for {@code diagramId}.
     */
    public static Result replay(IArchimateModel mergedBaseModel, String diagramId, List<DiagramVisualChange> changes,
            Map<String, VisualDecision> decisions) {
        if (mergedBaseModel == null) {
            throw new IllegalArgumentException("mergedBaseModel is required"); //$NON-NLS-1$
        }
        if (diagramId == null || diagramId.isEmpty()) {
            throw new IllegalArgumentException("diagramId is required"); //$NON-NLS-1$
        }
        IArchimateModel resultModel = EcoreUtil.copy(mergedBaseModel);
        EObject found = MergeUtils.findObjectById(resultModel, diagramId);
        IDiagramModel resultDiagram = found instanceof IDiagramModel dm ? dm : null;
        if (resultDiagram == null) {
            return new Result(resultModel, null);
        }

        List<DiagramVisualChange> patches = new ArrayList<>();
        for (DiagramVisualChange change : changes) {
            VisualDecision decision = decisions.getOrDefault(change.id(), VisualDecision.UNRESOLVED);
            if (decision != VisualDecision.UNRESOLVED) {
                patches.add(change);
            }
        }
        patches.sort(applyOrder());
        for (DiagramVisualChange change : patches) {
            VisualDecision decision = decisions.getOrDefault(change.id(), VisualDecision.UNRESOLVED);
            if (decision == VisualDecision.REMOTE) {
                applyRemote(change, resultDiagram, resultModel);
            }
            else if (decision == VisualDecision.LOCAL) {
                applyLocal(change, resultDiagram, resultModel);
            }
        }
        return new Result(resultModel, resultDiagram);
    }

    static void applyRemote(DiagramVisualChange change, IDiagramModel resultDiagram, IArchimateModel resultModel) {
        if (change == null || resultDiagram == null) {
            return;
        }
        switch (change.kind()) {
            case ADDED -> removeById(resultDiagram, change.id());
            case DELETED -> insertFromRemote(change, resultDiagram, resultModel);
            case MOVED, CHANGED -> restyleFrom(change.remoteObject(), resultDiagram);
            default -> {
            }
        }
    }

    static void applyLocal(DiagramVisualChange change, IDiagramModel resultDiagram, IArchimateModel resultModel) {
        if (change == null || resultDiagram == null) {
            return;
        }
        switch (change.kind()) {
            case ADDED -> {
            }
            case DELETED -> removeById(resultDiagram, change.id());
            case MOVED, CHANGED -> restyleFrom(change.localObject(), resultDiagram);
            default -> {
            }
        }
    }

    private static void removeById(IDiagramModel resultDiagram, String id) {
        EObject obj = findInDiagram(resultDiagram, id);
        if (obj instanceof IDiagramModelConnection conn) {
            disconnect(conn);
            EcoreUtil.remove(conn);
        }
        else if (obj != null) {
            EcoreUtil.delete(obj, true);
        }
    }

    private static void insertFromRemote(DiagramVisualChange change, IDiagramModel resultDiagram,
            IArchimateModel resultModel) {
        if (findInDiagram(resultDiagram, change.id()) != null) {
            return;
        }
        EObject remote = change.remoteObject();
        if (remote instanceof IDiagramModelObject remoteNode) {
            IDiagramModelObject copy = EcoreUtil.copy(remoteNode);
            stripConnections(copy);
            rebindConcepts(copy, resultModel);
            IDiagramModelContainer dest = resolveParent(remoteNode.eContainer(), resultDiagram);
            if (dest == null) {
                dest = resultDiagram;
            }
            dest.getChildren().add(copy);
        }
        else if (remote instanceof IDiagramModelConnection remoteConn) {
            IConnectable src = findConnectable(resultDiagram, MergeUtils.getObjectId(remoteConn.getSource()));
            IConnectable tgt = findConnectable(resultDiagram, MergeUtils.getObjectId(remoteConn.getTarget()));
            if (src == null || tgt == null) {
                return;
            }
            IDiagramModelConnection copy = EcoreUtil.copy(remoteConn);
            rebindConcepts(copy, resultModel);
            copy.connect(src, tgt);
        }
    }

    private static void restyleFrom(EObject source, IDiagramModel resultDiagram) {
        if (source == null) {
            return;
        }
        String id = MergeUtils.getObjectId(source);
        EObject resultObj = findInDiagram(resultDiagram, id);
        if (resultObj instanceof IDiagramModelObject resultNode && source instanceof IDiagramModelObject sourceNode) {
            copyBounds(sourceNode, resultNode);
            if (resultNode instanceof INameable nameable) {
                nameable.setName(sourceNode.getName());
            }
            resultNode.setFillColor(sourceNode.getFillColor());
            resultNode.setLineColor(sourceNode.getLineColor());
            resultNode.setLineWidth(sourceNode.getLineWidth());
        }
        else if (resultObj instanceof IDiagramModelConnection resultConn
                && source instanceof IDiagramModelConnection sourceConn) {
            resultConn.setLineColor(sourceConn.getLineColor());
            resultConn.setLineWidth(sourceConn.getLineWidth());
            resultConn.setType(sourceConn.getType());
            resultConn.getBendpoints().clear();
            for (IDiagramModelBendpoint bp : sourceConn.getBendpoints()) {
                resultConn.getBendpoints().add(EcoreUtil.copy(bp));
            }
            IConnectable src = findConnectable(resultDiagram, MergeUtils.getObjectId(sourceConn.getSource()));
            IConnectable tgt = findConnectable(resultDiagram, MergeUtils.getObjectId(sourceConn.getTarget()));
            if (src != null && tgt != null) {
                resultConn.connect(src, tgt);
            }
        }
    }

    private static void copyBounds(IDiagramModelObject from, IDiagramModelObject to) {
        IBounds src = from.getBounds();
        if (src == null) {
            return;
        }
        IBounds dest = to.getBounds();
        if (dest == null) {
            to.setBounds(EcoreUtil.copy(src));
            return;
        }
        dest.setX(src.getX());
        dest.setY(src.getY());
        dest.setWidth(src.getWidth());
        dest.setHeight(src.getHeight());
    }

    private static void stripConnections(IDiagramModelObject obj) {
        obj.getSourceConnections().clear();
        obj.getTargetConnections().clear();
        if (obj instanceof IDiagramModelContainer container) {
            for (IDiagramModelObject child : container.getChildren()) {
                stripConnections(child);
            }
        }
    }

    private static void rebindConcepts(EObject root, IArchimateModel resultModel) {
        if (resultModel == null || root == null) {
            return;
        }
        rebindOne(root, resultModel);
        for (Iterator<EObject> it = root.eAllContents(); it.hasNext();) {
            rebindOne(it.next(), resultModel);
        }
    }

    private static void rebindOne(EObject obj, IArchimateModel resultModel) {
        if (obj instanceof IDiagramModelArchimateObject dmo) {
            IArchimateElement el = dmo.getArchimateElement();
            if (el != null) {
                EObject found = MergeUtils.findObjectById(resultModel, el.getId());
                if (found instanceof IArchimateElement localEl) {
                    dmo.setArchimateElement(localEl);
                }
            }
        }
        if (obj instanceof IDiagramModelArchimateConnection dmc) {
            IArchimateRelationship rel = dmc.getArchimateRelationship();
            if (rel != null) {
                EObject found = MergeUtils.findObjectById(resultModel, rel.getId());
                if (found instanceof IArchimateRelationship localRel) {
                    dmc.setArchimateRelationship(localRel);
                }
            }
        }
    }

    private static IDiagramModelContainer resolveParent(EObject remoteParent, IDiagramModel resultDiagram) {
        if (remoteParent instanceof IDiagramModel) {
            return resultDiagram;
        }
        if (remoteParent instanceof IIdentifier ident) {
            EObject found = findInDiagram(resultDiagram, ident.getId());
            if (found instanceof IDiagramModelContainer container) {
                return container;
            }
        }
        return resultDiagram;
    }

    private static IConnectable findConnectable(IDiagramModel diagram, String id) {
        EObject found = findInDiagram(diagram, id);
        return found instanceof IConnectable c ? c : null;
    }

    static EObject findInDiagram(IDiagramModel diagram, String id) {
        if (diagram == null || id == null || id.isEmpty()) {
            return null;
        }
        if (id.equals(diagram.getId())) {
            return diagram;
        }
        for (Iterator<EObject> it = diagram.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IIdentifier ident && id.equals(ident.getId())) {
                return o;
            }
        }
        return null;
    }

    private static void disconnect(IDiagramModelConnection conn) {
        if (conn.getSource() instanceof IConnectable src) {
            src.getSourceConnections().remove(conn);
        }
        if (conn.getTarget() instanceof IConnectable tgt) {
            tgt.getTargetConnections().remove(conn);
        }
    }

    private static Comparator<DiagramVisualChange> applyOrder() {
        return Comparator
                .comparingInt((DiagramVisualChange c) -> switch (c.kind()) {
                    case DELETED -> c.isConnection() ? 1 : 0;
                    case ADDED -> 2;
                    case MOVED, CHANGED -> 3;
                    default -> 4;
                })
                .thenComparingInt(c -> depth(c.remoteObject() != null ? c.remoteObject() : c.localObject()));
    }

    private static int depth(EObject o) {
        int d = 0;
        while (o != null && !(o instanceof IDiagramModel)) {
            d++;
            o = o.eContainer();
        }
        return d;
    }
}
