/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.modelrepository.dialogs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.modelrepository.dialogs.DiagramVisualChange.AbsoluteBounds;
import com.archimatetool.modelrepository.dialogs.DiagramVisualChange.AbsolutePoint;
import com.archimatetool.modelrepository.dialogs.DiagramVisualChange.Kind;
import com.archimatetool.modelrepository.merge.MergeUtils;

/**
 * ID-matches diagram nodes and connections on two views and classifies visual-only differences.
 */
public final class DiagramVisualDiffBuilder {

    private DiagramVisualDiffBuilder() {
    }

    /**
     * @param remote theirs
     * @param local  ours (canvas)
     */
    public static List<DiagramVisualChange> build(IDiagramModel remote, IDiagramModel local) {
        List<DiagramVisualChange> result = new ArrayList<>();
        Map<String, EObject> remoteIndex = index(remote);
        Map<String, EObject> localIndex = index(local);

        for (Map.Entry<String, EObject> e : localIndex.entrySet()) {
            EObject localObj = e.getValue();
            EObject remoteObj = remoteIndex.get(e.getKey());
            if (remoteObj == null) {
                result.add(create(Kind.ADDED, e.getKey(), localObj, null, false));
            }
            else {
                DiagramVisualChange change = compareMatched(e.getKey(), localObj, remoteObj);
                if (change != null) {
                    result.add(change);
                }
            }
        }

        for (Map.Entry<String, EObject> e : remoteIndex.entrySet()) {
            if (!localIndex.containsKey(e.getKey())) {
                result.add(create(Kind.DELETED, e.getKey(), null, e.getValue(), false));
            }
        }

        result.sort((a, b) -> {
            int k = Integer.compare(a.kind().ordinal(), b.kind().ordinal());
            return k != 0 ? k : a.label().compareToIgnoreCase(b.label());
        });
        return result;
    }

    private static DiagramVisualChange compareMatched(String id, EObject localObj, EObject remoteObj) {
        if (localObj instanceof IDiagramModelObject localNode && remoteObj instanceof IDiagramModelObject remoteNode) {
            boolean moved = boundsDiffer(localNode, remoteNode);
            boolean restyled = !eq(nameOf(localNode), nameOf(remoteNode))
                    || !eq(localNode.getFillColor(), remoteNode.getFillColor())
                    || !eq(localNode.getLineColor(), remoteNode.getLineColor())
                    || localNode.getLineWidth() != remoteNode.getLineWidth();
            if (!moved && !restyled) {
                return null;
            }
            Kind kind = restyled ? Kind.CHANGED : Kind.MOVED;
            return create(kind, id, localObj, remoteObj, moved);
        }
        if (localObj instanceof IDiagramModelConnection localConn
                && remoteObj instanceof IDiagramModelConnection remoteConn) {
            boolean restyled = !Objects.equals(MergeUtils.getObjectId(localConn.getSource()),
                    MergeUtils.getObjectId(remoteConn.getSource()))
                    || !Objects.equals(MergeUtils.getObjectId(localConn.getTarget()),
                            MergeUtils.getObjectId(remoteConn.getTarget()))
                    || !eq(localConn.getLineColor(), remoteConn.getLineColor())
                    || localConn.getLineWidth() != remoteConn.getLineWidth()
                    || localConn.getType() != remoteConn.getType()
                    || !bendpointsEqual(localConn, remoteConn);
            if (!restyled) {
                return null;
            }
            return create(Kind.CHANGED, id, localObj, remoteObj, false);
        }
        return create(Kind.CHANGED, id, localObj, remoteObj, false);
    }

    private static DiagramVisualChange create(Kind kind, String id, EObject localObj, EObject remoteObj,
            boolean boundsChanged) {
        EObject labelled = localObj != null ? localObj : remoteObj;
        AbsoluteBounds remoteBounds = null;
        AbsolutePoint src = null;
        AbsolutePoint tgt = null;
        if (remoteObj instanceof IDiagramModelObject remoteNode) {
            remoteBounds = absoluteBounds(remoteNode);
        }
        if (remoteObj instanceof IDiagramModelConnection remoteConn) {
            src = endpointCenter(remoteConn.getSource());
            tgt = endpointCenter(remoteConn.getTarget());
        }
        return new DiagramVisualChange(kind, id, MergeLabels.labelOf(labelled), localObj, remoteObj, boundsChanged, remoteBounds,
                src, tgt);
    }

    static Map<String, EObject> index(IDiagramModel diagram) {
        Map<String, EObject> map = new LinkedHashMap<>();
        if (diagram == null) {
            return map;
        }
        for (Iterator<EObject> it = diagram.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if ((o instanceof IDiagramModelObject || o instanceof IDiagramModelConnection)
                    && o instanceof IIdentifier ident && ident.getId() != null && !ident.getId().isEmpty()) {
                map.putIfAbsent(ident.getId(), o);
            }
        }
        return map;
    }

    static AbsoluteBounds absoluteBounds(IDiagramModelObject dmo) {
        if (dmo == null || dmo.getBounds() == null) {
            return null;
        }
        IBounds self = dmo.getBounds();
        int x = self.getX();
        int y = self.getY();
        EObject parent = dmo.eContainer();
        while (parent instanceof IDiagramModelObject parentObj) {
            IBounds b = parentObj.getBounds();
            if (b != null) {
                x += b.getX();
                y += b.getY();
            }
            parent = parent.eContainer();
        }
        return new AbsoluteBounds(x, y, self.getWidth(), self.getHeight());
    }

    private static AbsolutePoint endpointCenter(EObject endpoint) {
        if (endpoint instanceof IDiagramModelObject dmo) {
            AbsoluteBounds b = absoluteBounds(dmo);
            return b != null ? b.center() : null;
        }
        return null;
    }

    private static boolean boundsDiffer(IDiagramModelObject a, IDiagramModelObject b) {
        IBounds ba = a.getBounds();
        IBounds bb = b.getBounds();
        if (ba == null || bb == null) {
            return ba != bb;
        }
        return ba.getX() != bb.getX() || ba.getY() != bb.getY() || ba.getWidth() != bb.getWidth()
                || ba.getHeight() != bb.getHeight();
    }

    private static boolean bendpointsEqual(IDiagramModelConnection a, IDiagramModelConnection b) {
        List<IDiagramModelBendpoint> pa = a.getBendpoints();
        List<IDiagramModelBendpoint> pb = b.getBendpoints();
        if (pa.size() != pb.size()) {
            return false;
        }
        for (int i = 0; i < pa.size(); i++) {
            IDiagramModelBendpoint xa = pa.get(i);
            IDiagramModelBendpoint xb = pb.get(i);
            if (xa.getStartX() != xb.getStartX() || xa.getStartY() != xb.getStartY() || xa.getEndX() != xb.getEndX()
                    || xa.getEndY() != xb.getEndY()) {
                return false;
            }
        }
        return true;
    }

    private static String nameOf(EObject obj) {
        return obj instanceof INameable nameable ? nameable.getName() : null;
    }

    private static boolean eq(String a, String b) {
        String sa = a == null || a.isEmpty() ? null : a;
        String sb = b == null || b.isEmpty() ? null : b;
        return Objects.equals(sa, sb);
    }
}
