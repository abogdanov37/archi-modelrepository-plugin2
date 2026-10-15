/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.modelrepository.dialogs;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IDiagramModelConnection;

/**
 * One visual difference between the LOCAL and REMOTE copies of a diagram object or connection.
 */
public record DiagramVisualChange(Kind kind, String id, String label, EObject localObject, EObject remoteObject,
        boolean boundsChanged, AbsoluteBounds remoteBounds, AbsolutePoint remoteSourcePoint,
        AbsolutePoint remoteTargetPoint) {

    public enum Kind {
        ADDED, DELETED, MOVED, CHANGED
    }

    public record AbsoluteBounds(int x, int y, int width, int height) {
        public AbsolutePoint center() {
            return new AbsolutePoint(x + width / 2, y + height / 2);
        }
    }

    public record AbsolutePoint(int x, int y) {
    }

    public boolean isConnection() {
        return localObject instanceof IDiagramModelConnection || remoteObject instanceof IDiagramModelConnection;
    }
}
