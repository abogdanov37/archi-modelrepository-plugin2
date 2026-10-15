/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.modelrepository.merge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.BasicMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.compare.Comparison;
import org.eclipse.emf.compare.Conflict;
import org.eclipse.emf.compare.Diff;
import org.eclipse.emf.compare.DifferenceKind;
import org.eclipse.emf.compare.DifferenceSource;
import org.eclipse.emf.compare.ReferenceChange;
import org.eclipse.emf.compare.merge.BatchMerger;
import org.eclipse.emf.compare.merge.IMerger;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.archimatetool.model.IArchimateModel;

/**
 * Builds an in-memory merge of LOCAL+REMOTE with REAL conflicts left on the LOCAL side.
 */
public final class MergePreview {

	private MergePreview() {
	}

	/**
	 * Copies the three models, applies non-conflict LEFT diffs, heals, and returns the ours copy.
	 *
	 * @param ours    LOCAL / right
	 * @param theirs  REMOTE / left
	 * @param origin  merge-base, or {@code null}
	 */
	public static IArchimateModel mergeExceptConflicts(IArchimateModel ours, IArchimateModel theirs,
			IArchimateModel origin) {
		if (ours == null || theirs == null) {
			throw new IllegalArgumentException("ours and theirs are required"); //$NON-NLS-1$
		}
		IArchimateModel oursCopy = copyIsolated(ours, "mem://preview-ours"); //$NON-NLS-1$
		IArchimateModel theirsCopy = copyIsolated(theirs, "mem://preview-theirs"); //$NON-NLS-1$
		IArchimateModel originCopy = origin != null ? copyIsolated(origin, "mem://preview-base") : null; //$NON-NLS-1$

		Comparison preview = MergeFactory.createComparison(theirsCopy, oursCopy, originCopy);
		List<Diff> toApply = filterDiffsToApply(preview, Map.of(), Set.of());
		if (!toApply.isEmpty()) {
			IMerger.Registry registry = IMerger.RegistryImpl.createStandaloneInstance();
			new BatchMerger(registry).copyAllLeftToRight(toApply, new BasicMonitor());
		}
		new ModelHealer(oursCopy).heal(preview, theirsCopy);
		return oursCopy;
	}

	/**
	 * LEFT diffs safe for {@link BatchMerger}: skips conflict members, local-only objects, and
	 * destructive list ops when the resolution is not REMOTE.
	 */
	public static List<Diff> filterDiffsToApply(Comparison comparison, Map<Diff, Boolean> resolutions) {
		return filterDiffsToApply(comparison, resolutions, Set.of());
	}

	/**
	 * Same as {@link #filterDiffsToApply(Comparison, Map)} but also skips LEFT diffs whose diagram
	 * object (or containing object/connection) is in {@code keepLocalVisualIds}.
	 */
	public static List<Diff> filterDiffsToApply(Comparison comparison, Map<Diff, Boolean> resolutions,
			Set<String> keepLocalVisualIds) {
		return filterDiffsToApply(comparison, resolutions, keepLocalVisualIds, Set.of());
	}

	/**
	 * Same as {@link #filterDiffsToApply(Comparison, Map, Set)} but also skips {@code forcedLocalDiffs}
	 * (REMOTE folder content changes the user rejected in the folder merge editor).
	 */
	public static List<Diff> filterDiffsToApply(Comparison comparison, Map<Diff, Boolean> resolutions,
			Set<String> keepLocalVisualIds, Set<Diff> forcedLocalDiffs) {
		List<Diff> toApply = new ArrayList<>();
		Set<Diff> forcedLocal = forcedLocalDiffs != null ? forcedLocalDiffs : Set.of();
		Set<EObject> protectedObjects = new HashSet<>();
		Map<Diff, Boolean> res = resolutions != null ? resolutions : Map.of();
		Set<String> keepLocal = keepLocalVisualIds != null ? keepLocalVisualIds : Set.of();

		for (Conflict conflict : comparison.getConflicts()) {
			for (Diff d : conflict.getDifferences()) {
				EObject obj = MergeUtils.getObjectFromDiff(d);
				if (obj != null) {
					protectedObjects.add(obj);
				}
			}
		}
		for (Diff diff : comparison.getDifferences()) {
			if (diff.getSource() == DifferenceSource.RIGHT) {
				EObject obj = MergeUtils.getObjectFromDiff(diff);
				if (obj != null) {
					protectedObjects.add(obj);
				}
			}
		}

		for (Diff diff : comparison.getDifferences()) {
			if (diff.getConflict() != null) {
				continue;
			}
			if (diff.getSource() != DifferenceSource.LEFT || forcedLocal.contains(diff)) {
				continue;
			}
			EObject remoteObj = MergeUtils.getObjectFromDiff(diff);
			if (remoteObj != null && protectedObjects.contains(remoteObj)) {
				continue;
			}
			if (!keepLocal.isEmpty()) {
				String visualId = visualIdOf(diff);
				if (visualId != null && keepLocal.contains(visualId)) {
					continue;
				}
			}
			if (diff instanceof ReferenceChange rc && rc.getReference().isMany()) {
				boolean isRemoteResolution = Boolean.TRUE.equals(res.get(diff));
				if (!isRemoteResolution
						&& (rc.getKind() == DifferenceKind.DELETE || rc.getKind() == DifferenceKind.MOVE)) {
					continue;
				}
			}
			if (!(diff.getKind() == DifferenceKind.DELETE && diff.getMatch().getLeft() == null)) {
				toApply.add(diff);
			}
		}
		return toApply;
	}

	private static String visualIdOf(Diff diff) {
		String id = MergeUtils.getDiagramVisualId(MergeUtils.getObjectFromDiff(diff));
		if (id != null && !id.isEmpty()) {
			return id;
		}
		if (diff.getMatch() != null) {
			id = MergeUtils.getDiagramVisualId(diff.getMatch().getLeft());
			if (id != null && !id.isEmpty()) {
				return id;
			}
			id = MergeUtils.getDiagramVisualId(diff.getMatch().getRight());
			if (id != null && !id.isEmpty()) {
				return id;
			}
		}
		return null;
	}

	private static IArchimateModel copyIsolated(IArchimateModel model, String uri) {
		IArchimateModel copy = EcoreUtil.copy(model);
		ResourceSet rs = new ResourceSetImpl();
		Resource resource = rs.createResource(URI.createURI(uri));
		resource.getContents().add(copy);
		return copy;
	}
}
