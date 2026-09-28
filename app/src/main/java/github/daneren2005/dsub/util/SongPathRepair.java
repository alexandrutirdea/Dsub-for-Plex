package github.daneren2005.dsub.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs song paths the database still has registered in the old cache layout with the
 * files that were moved out of it. See {@link CacheLayoutMigration}.
 *
 * The old layout kept nothing about a song but its file name, so the name is all there is
 * to pair on - and only where it is unambiguous on both sides. A wrong pairing would hand
 * one song's plays to another; a missed one just waits for the next listing to register
 * the song where it is now.
 */
public final class SongPathRepair {
	private SongPathRepair() {
	}

	/** Each stale path, mapped to the one unregistered file carrying the same name. */
	public static Map<String, String> match(Collection<String> stalePaths, Collection<String> unregisteredFiles) {
		Map<String, List<String>> stale = byName(stalePaths);
		Map<String, List<String>> files = byName(unregisteredFiles);

		Map<String, String> moves = new LinkedHashMap<String, String>();
		for(Map.Entry<String, List<String>> entry: stale.entrySet()) {
			List<String> candidates = files.get(entry.getKey());
			if(entry.getValue().size() == 1 && candidates != null && candidates.size() == 1) {
				moves.put(entry.getValue().get(0), candidates.get(0));
			}
		}
		return moves;
	}

	private static Map<String, List<String>> byName(Collection<String> paths) {
		Map<String, List<String>> result = new HashMap<String, List<String>>();
		for(String path: paths) {
			String name = path.substring(path.lastIndexOf('/') + 1);
			List<String> named = result.get(name);
			if(named == null) {
				named = new ArrayList<String>();
				result.put(name, named);
			}
			named.add(path);
		}
		return result;
	}
}
