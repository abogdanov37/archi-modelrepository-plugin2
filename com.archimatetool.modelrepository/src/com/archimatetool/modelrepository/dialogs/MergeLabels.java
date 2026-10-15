/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.modelrepository.dialogs;
import org.eclipse.emf.ecore.EObject;
import com.archimatetool.editor.ui.ArchiLabelProvider;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.INameable;
/**
 * Shared row labels for merge editors (diagram overlay, folder contents).
 */
public final class MergeLabels {
    private MergeLabels() {
    }
    public static String labelOf(EObject obj) {
        if (obj instanceof IDiagramModelConnection conn) {
            return labelOf(conn.getTarget()) + " -> " + labelOf(conn.getSource()); //$NON-NLS-1$
        }
        if (obj instanceof INameable nameable) {
            String name = nameable.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }        
        if (obj != null) {
            String label = ArchiLabelProvider.INSTANCE.getLabel(obj);
            if (label != null && !label.isBlank()) {
                return label;
            }
            return obj.eClass().getName();
        }
        return ""; //$NON-NLS-1$
    }
    /**
     * Archi type name of {@code obj}; diagram components resolve to their ArchiMate concept.
     */
    public static String typeLabel(EObject obj) {
        if (obj instanceof IDiagramModelArchimateComponent component && component.getArchimateConcept() != null) {
            obj = component.getArchimateConcept();
        }
        if (obj == null) {
            return ""; //$NON-NLS-1$
        }
        String name = ArchiLabelProvider.INSTANCE.getDefaultName(obj.eClass());
        if (name != null && !name.isBlank()) {
            return name;
        }
        return humanizeClassName(obj.eClass().getName());
    }
    public static String humanizeClassName(String className) {
        if (className == null || className.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < className.length(); i++) {
            char c = className.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(className.charAt(i - 1))) {
                text.append(' ');
            }
            text.append(c);
        }
        return text.toString();
    }
}