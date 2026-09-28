/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
*/

package github.daneren2005.dsub.util;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import github.daneren2005.dsub.domain.MusicDirectory.Entry;

/**
 * The "mix" a playlist can be played as, ported from the plex-love web app: drop tracks
 * played too recently, too seldom or too often, then shuffle so the same artist does not
 * come round again too soon. Every step reads fields the Plex playlist listing already
 * carries ({@code lastPlayed}, {@code playCount}, the album artist), so the whole thing
 * runs on data in hand without another request.
 *
 * "Loved only" is not a step here: it is done by pointing the mix at the "Loved
 * Tracks" playlist, exactly as the web app does.
 */
public final class PlaylistMix {
	private static final String TAG = PlaylistMix.class.getSimpleName();

	/**
	 * How far either side of the middle of the loudness range a track can sit and still be
	 * somewhere the walk might begin.
	 */
	private static final float MIDDLE_BAND = 0.15f;

	/**
	 * How much further than the nearest track another can be and still count as just as
	 * good a thing to play next. Small against the step the ordering actually achieves, so
	 * this buys variety without loosening the order it produces.
	 */
	private static final float NEAR_ENOUGH = 0.015f;

	/**
	 * What each measurement counts for.
	 *
	 * Loudness and range are put on a nought to one scale across the set, so the difference
	 * between two neighbouring tracks on either is typically a few hundredths. Singing is
	 * not a scale but a yes or no, so the difference between two tracks is either nothing
	 * at all or the whole of its weight - which is why it is weighted an order of magnitude
	 * below the other two rather than beside them. Weighted level with the rest, as it once
	 * was, a single yes against a no cost six times what the walk's own median step did,
	 * and the ordering became a sort into sung and unsung with the levels only deciding the
	 * order inside each.
	 */
	private static final float LOUDNESS_WEIGHT = 1f;
	private static final float RANGE_WEIGHT = 0.5f;
	private static final float VOICE_WEIGHT = 0.08f;

	private static final java.util.Random RANDOM = new java.util.Random();

	private PlaylistMix() {}

	/**
	 * The tracks a mix would draw on: rested for at least {@code cooldownDays}, with a
	 * play count of at least {@code minPlays} and under {@code maxPlays}, and running at
	 * least {@code minSeconds} and under {@code maxSeconds}. Any threshold at zero (or
	 * less) drops that test, so with all of them off this is the whole playlist and the
	 * mix becomes a plain artist-spaced shuffle.
	 */
	public static List<Entry> eligibleTracks(List<Entry> tracks, int cooldownDays, int minPlays, int maxPlays,
			int minSeconds, int maxSeconds) {
		if(tracks == null) {
			return new ArrayList<Entry>();
		}

		long cutoff = restedCutoff(cooldownDays);
		List<Entry> eligible = new ArrayList<Entry>();
		for(Entry track : tracks) {
			if(isEligible(track, cooldownDays, cutoff, minPlays, maxPlays, minSeconds, maxSeconds)) {
				eligible.add(track);
			}
		}
		return eligible;
	}

	/**
	 * How many tracks the thresholds leave, counted without building the list, so the
	 * pill can show it every time a threshold or the playlist changes. Shares its test
	 * with {@link #eligibleTracks}, so the number cannot drift from what actually plays.
	 */
	public static int countEligible(List<Entry> tracks, int cooldownDays, int minPlays, int maxPlays,
			int minSeconds, int maxSeconds) {
		if(tracks == null) {
			return 0;
		}

		long cutoff = restedCutoff(cooldownDays);
		int count = 0;
		for(Entry track : tracks) {
			if(isEligible(track, cooldownDays, cutoff, minPlays, maxPlays, minSeconds, maxSeconds)) {
				count++;
			}
		}
		return count;
	}

	/** Epoch seconds, matching what Plex reports lastPlayed in. */
	private static long restedCutoff(int cooldownDays) {
		return (System.currentTimeMillis() / 1000L) - (long) cooldownDays * 86400L;
	}

	/**
	 * A track Plex has reported no count for is taken as never played, which is what an
	 * absent count means - so it fails any floor and passes any ceiling. Note that puts
	 * it on opposite sides of the two play tests, and of the cooldown, which reads
	 * never-played as rested forever: asking for a floor is asking for old favourites,
	 * and a track with no history is not one.
	 */
	private static boolean isEligible(Entry track, int cooldownDays, long cutoff, int minPlays, int maxPlays,
			int minSeconds, int maxSeconds) {
		if(cooldownDays > 0) {
			Long lastPlayed = track.getLastPlayed();
			if(lastPlayed != null && lastPlayed >= cutoff) {
				return false;
			}
		}

		Integer playCount = track.getPlayCount();
		int plays = playCount == null ? 0 : playCount;
		if(minPlays > 0 && plays < minPlays) {
			return false;
		}
		if(maxPlays > 0 && plays >= maxPlays) {
			return false;
		}

		if(minSeconds > 0 || maxSeconds > 0) {
			Integer duration = track.getDuration();
			// Unlike an absent play count, which genuinely means never played, an absent
			// duration means the server did not say - and a track of unknown length does
			// not belong in a list whose whole definition is a length. Plex reports one on
			// every track in a playlist listing, so this is the odd case, not the rule.
			if(duration == null) {
				return false;
			}
			if(minSeconds > 0 && duration < minSeconds) {
				return false;
			}
			// Exclusive at the top and inclusive at the bottom, matching the play counts
			// above, so a track exactly on a boundary lands in one bucket and not both.
			if(maxSeconds > 0 && duration >= maxSeconds) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Shuffles keeping the same artist at least {@code gap} tracks apart wherever it
	 * can. Every track is kept; a gap of zero is a plain shuffle. When only tracks by
	 * a recently-seen artist remain, the least-recently-seen of them is taken rather
	 * than stalling - so a playlist heavy on one artist still finishes.
	 *
	 * Artists are compared on the album artist (Plex's grandparentTitle), matching the
	 * web app: a compilation spaced on each track's own performer would barely space
	 * at all.
	 */
	public static List<Entry> artistGapShuffle(List<Entry> tracks, int gap) {
		List<Entry> pool = new ArrayList<Entry>(tracks);
		Collections.shuffle(pool);
		if(gap <= 0) {
			return pool;
		}

		List<Entry> result = new ArrayList<Entry>(pool.size());
		List<String> recent = new ArrayList<String>();
		while(!pool.isEmpty()) {
			int pick = -1;
			for(int i = 0; i < pool.size(); i++) {
				if(!inRecentWindow(recent, gap, artistKey(pool.get(i)))) {
					pick = i;
					break;
				}
			}
			if(pick == -1) {
				// Every remaining track repeats a recent artist; take the first, which
				// is the one whose artist fell out of the window longest ago.
				pick = 0;
			}

			Entry chosen = pool.remove(pick);
			result.add(chosen);
			recent.add(artistKey(chosen));
		}
		return result;
	}

	/**
	 * Whether there is enough measured about these tracks for {@link #flowOrder} to be
	 * ordering rather than guessing - which is worth knowing separately, so the screen can
	 * say when it has quietly shuffled instead.
	 */
	public static boolean canOrderByFlow(List<Entry> tracks) {
		if(tracks == null || tracks.size() < 3) {
			return false;
		}

		int measured = 0;
		for(Entry track : tracks) {
			if(track.getLoudness() != null) {
				measured++;
			}
		}
		return measured * 2 >= tracks.size();
	}

	/**
	 * Orders tracks so that each one sounds like a reasonable thing to hear after the last.
	 *
	 * Shuffle asks nothing of a track but who made it, so a whispered acoustic piece can
	 * land between two loud ones and the set lurches. What the server's analysis measured -
	 * how loud a recording is, how far it travels between its quietest and loudest, whether
	 * anybody sings on it - is enough to keep neighbours in the same territory.
	 *
	 * Built by walking rather than sorting. Sorting on loudness alone would be a ramp from
	 * the quietest track to the loudest, which is smooth and awful; walking takes the
	 * nearest track that the artist gap allows, and being turned away from the nearest is
	 * what keeps it wandering rather than climbing. It starts from the middle of the range
	 * for the same reason - an end is a worse place to begin than the middle.
	 *
	 * A track the server has said nothing about takes no part in the walk at all. It cannot:
	 * there is no answer to how far it sits from anything, and every stand-in answer decides
	 * its place in the queue for it - a small one puts every unanalysed track at the front, a
	 * large one puts them all at the back, and either way they arrive in a block, since
	 * whatever is true of one is true of all of them. They are spread evenly through the
	 * finished walk instead, which is the one thing that is actually wanted of them: an
	 * unanalysed track sitting among the ordinary ones rather than shunted to an end.
	 *
	 * When hardly anything has been measured there is nothing to order on at all, and this
	 * falls back on {@link #artistGapShuffle}.
	 */
	public static List<Entry> flowOrder(List<Entry> tracks, int gap) {
		List<Entry> pool = new ArrayList<Entry>(tracks);
		if(pool.size() < 3) {
			return pool;
		}

		// Under half measured and the ordering would be mostly invented, which is a shuffle
		// with a better name on it. Better to say so by behaving like one.
		if(!canOrderByFlow(pool)) {
			return artistGapShuffle(pool, gap);
		}

		Map<Entry, Features> features = featuresOf(pool);

		List<Entry> measured = new ArrayList<Entry>();
		List<Entry> unmeasured = new ArrayList<Entry>();
		for(Entry track : pool) {
			(track.getLoudness() == null ? unmeasured : measured).add(track);
		}

		// Shuffled before the walk begins, so that candidates the same distance away are
		// not always resolved in the order the playlist happens to be stored in.
		Collections.shuffle(measured, RANDOM);

		Entry current = startingPoint(measured, features);

		List<Entry> walk = new ArrayList<Entry>(measured.size());
		List<String> recent = new ArrayList<String>();
		measured.remove(current);
		walk.add(current);
		recent.add(artistKey(current));

		while(!measured.isEmpty()) {
			Entry next = nearest(measured, features, features.get(current), recent, gap);
			measured.remove(next);
			walk.add(next);
			recent.add(artistKey(next));
			current = next;
		}

		List<Entry> result = spread(walk, unmeasured, gap);

		Log.i(TAG, "Flow ordered " + result.size() + " tracks from \"" + result.get(0).getTitle()
				+ "\": mean step between neighbours "
				+ meanStep(result, features) + ", against " + meanStep(tracks, features)
				+ " in the order they came in"
				+ (unmeasured.isEmpty() ? ""
						: ", with " + unmeasured.size() + " the server has not analysed"
								+ " spread through it"));
		return result;
	}

	/**
	 * Puts the tracks that took no part in the walk back into it at even intervals.
	 *
	 * Spaced rather than appended, so that a playlist half of which the server has not got
	 * round to does not play as an ordered half followed by a shuffled one. Where the track
	 * about to go in shares an artist with what it would land next to, the next one along is
	 * taken instead - the walk keeps its own artists apart and this is not the place to
	 * undo that.
	 */
	private static List<Entry> spread(List<Entry> walk, List<Entry> loose, int gap) {
		if(loose.isEmpty()) {
			return walk;
		}

		List<Entry> waiting = artistGapShuffle(loose, gap);
		List<Entry> result = new ArrayList<Entry>(walk.size() + waiting.size());
		double every = (double) (walk.size() + waiting.size()) / (double) waiting.size();
		double next = every;

		for(Entry track : walk) {
			result.add(track);
			while(!waiting.isEmpty() && result.size() >= next) {
				result.add(takeUnlikeNeighbour(waiting, result));
				next += every;
			}
		}
		result.addAll(waiting);
		return result;
	}

	/**
	 * The first track waiting that is not by whoever was just played, or simply the first
	 * when they all are.
	 */
	private static Entry takeUnlikeNeighbour(List<Entry> waiting, List<Entry> soFar) {
		String previous = soFar.isEmpty() ? null : artistKey(soFar.get(soFar.size() - 1));
		for(int i = 0; i < waiting.size(); i++) {
			if(!artistKey(waiting.get(i)).equals(previous)) {
				return waiting.remove(i);
			}
		}
		return waiting.remove(0);
	}

	/**
	 * How far the average pair of neighbours sits apart, which is the thing the ordering
	 * exists to make small. Logged against the incoming order so it is visible whether any
	 * of this actually bought anything on a given playlist.
	 */
	private static float meanStep(List<Entry> order, Map<Entry, Features> features) {
		if(order.size() < 2) {
			return 0f;
		}

		float total = 0;
		int steps = 0;
		for(int i = 0; i + 1 < order.size(); i++) {
			Features from = features.get(order.get(i));
			Features to = features.get(order.get(i + 1));
			if(from != null && to != null) {
				total += distance(from, to);
				steps++;
			}
		}
		return steps == 0 ? 0f : total / steps;
	}

	/**
	 * Where the walk begins: one of the tracks in the middle of the loudness range, picked
	 * at random.
	 *
	 * The middle rather than an end, because a set that opens at its quietest or its
	 * loudest has nowhere to go but one way. Random rather than the single track nearest
	 * the middle, because the walk is otherwise wholly decided by where it starts - the
	 * same filtered playlist would come back in the same order every time, which is not
	 * what anybody wants from a button they press twice.
	 */
	private static Entry startingPoint(List<Entry> pool, Map<Entry, Features> features) {
		List<Entry> middle = new ArrayList<Entry>();
		for(Entry track : pool) {
			float loudness = features.get(track).loudness;
			if(loudness >= 0.5f - MIDDLE_BAND && loudness <= 0.5f + MIDDLE_BAND) {
				middle.add(track);
			}
		}
		if(!middle.isEmpty()) {
			return middle.get(RANDOM.nextInt(middle.size()));
		}

		// Nothing in the middle at all, which happens where the set is loud tracks and
		// quiet ones and nothing between. Whichever is nearest it will do.
		Entry nearestMiddle = pool.get(0);
		float best = Float.MAX_VALUE;
		for(Entry track : pool) {
			float fromMiddle = Math.abs(features.get(track).loudness - 0.5f);
			if(fromMiddle < best) {
				best = fromMiddle;
				nearestMiddle = track;
			}
		}
		return nearestMiddle;
	}

	/**
	 * The nearest track the artist gap allows, or simply the nearest when every one left
	 * repeats a recent artist - the same way {@link #artistGapShuffle} finishes a playlist
	 * that is heavy on one name rather than stalling on it.
	 *
	 * Anything within {@link #NEAR_ENOUGH} of the best is taken as being just as good and
	 * one of them is picked at random. Two tracks a hundredth apart are not audibly a
	 * better and a worse thing to play next, and insisting on the better one makes the
	 * whole order fall out of the starting track and nothing else.
	 */
	private static Entry nearest(List<Entry> pool, Map<Entry, Features> features, Features from,
			List<String> recent, int gap) {
		float bestAllowed = Float.MAX_VALUE;
		float bestAny = Float.MAX_VALUE;

		for(Entry candidate : pool) {
			float distance = distance(from, features.get(candidate));
			bestAny = Math.min(bestAny, distance);
			if(gap > 0 && inRecentWindow(recent, gap, artistKey(candidate))) {
				continue;
			}
			bestAllowed = Math.min(bestAllowed, distance);
		}

		boolean anyAllowed = bestAllowed < Float.MAX_VALUE;
		float ceiling = (anyAllowed ? bestAllowed : bestAny) + NEAR_ENOUGH;

		List<Entry> contenders = new ArrayList<Entry>();
		for(Entry candidate : pool) {
			if(anyAllowed && gap > 0 && inRecentWindow(recent, gap, artistKey(candidate))) {
				continue;
			}
			if(distance(from, features.get(candidate)) <= ceiling) {
				contenders.add(candidate);
			}
		}
		return contenders.get(RANDOM.nextInt(contenders.size()));
	}

	/**
	 * How unlike one track is to another, as a single number.
	 *
	 * Loudness carries the most because it is what a jump is actually heard as. The range
	 * matters rather less and in its own way - a track that breathes next to one that never
	 * lets up is a change of manner more than of volume. Singing counts for least, for the
	 * reason given where the weights are.
	 *
	 * A measurement only one of the two has says nothing about how far apart they are, so
	 * that axis is left out of the comparison rather than guessed at. Standing an absent
	 * measurement in the middle of the scale, as this used to, makes not knowing into a
	 * measurement of its own - one that every unanalysed track shares exactly, so they all
	 * end up the same distance from each other and clump into one block.
	 */
	private static float distance(Features from, Features to) {
		float total = 0f;
		if(from.hasLoudness && to.hasLoudness) {
			total += LOUDNESS_WEIGHT * Math.abs(from.loudness - to.loudness);
		}
		if(from.hasRange && to.hasRange) {
			total += RANGE_WEIGHT * Math.abs(from.range - to.range);
		}
		if(from.hasVoice && to.hasVoice) {
			total += VOICE_WEIGHT * Math.abs(from.voice - to.voice);
		}
		return total;
	}

	/** What is known about how one recording sounds, and which of it is actually known. */
	private static final class Features {
		final float loudness;
		final float range;
		final float voice;
		final boolean hasLoudness;
		final boolean hasRange;
		final boolean hasVoice;

		Features(Float loudness, float loudnessMin, float loudnessMax,
				Float range, float rangeMin, float rangeMax, Boolean voice) {
			this.hasLoudness = loudness != null;
			this.hasRange = range != null;
			this.hasVoice = voice != null;
			this.loudness = scale(loudness, loudnessMin, loudnessMax);
			this.range = scale(range, rangeMin, rangeMax);
			this.voice = voice == null ? 0.5f : (voice ? 1f : 0f);
		}
	}

	/**
	 * Loudness and range put on a nought to one scale across this particular set, so one
	 * decibel counts for as much as the set's own spread makes it worth. A playlist whose
	 * tracks are all within a decibel of each other is then ordered on the differences it
	 * does have, rather than on differences too small to hear.
	 */
	private static Map<Entry, Features> featuresOf(List<Entry> tracks) {
		float loudnessMin = Float.MAX_VALUE, loudnessMax = -Float.MAX_VALUE;
		float rangeMin = Float.MAX_VALUE, rangeMax = -Float.MAX_VALUE;
		for(Entry track : tracks) {
			if(track.getLoudness() != null) {
				loudnessMin = Math.min(loudnessMin, track.getLoudness());
				loudnessMax = Math.max(loudnessMax, track.getLoudness());
			}
			if(track.getLoudnessRange() != null) {
				rangeMin = Math.min(rangeMin, track.getLoudnessRange());
				rangeMax = Math.max(rangeMax, track.getLoudnessRange());
			}
		}

		Map<Entry, Features> features = new HashMap<Entry, Features>();
		for(Entry track : tracks) {
			features.put(track, new Features(
					track.getLoudness(), loudnessMin, loudnessMax,
					track.getLoudnessRange(), rangeMin, rangeMax,
					track.getVoiceActivity()));
		}
		return features;
	}

	/**
	 * Where a value sits in the set's own spread.
	 *
	 * The middle is returned for a value there is none of, and for a set with no spread to
	 * speak of. It is a placeholder rather than a reading in the first case: what it stands
	 * for is recorded alongside it, and {@link #distance} leaves an axis out rather than
	 * comparing against this.
	 */
	private static float scale(Float value, float min, float max) {
		if(value == null || min > max || max - min < 0.0001f) {
			return 0.5f;
		}
		return (value - min) / (max - min);
	}

	private static boolean inRecentWindow(List<String> recent, int gap, String artist) {
		int from = Math.max(0, recent.size() - gap);
		for(int i = from; i < recent.size(); i++) {
			if(recent.get(i).equals(artist)) {
				return true;
			}
		}
		return false;
	}

	private static String artistKey(Entry entry) {
		String artist = entry.getAlbumArtist();
		if(artist == null || artist.isEmpty()) {
			artist = entry.getArtist();
		}
		return artist == null ? "" : artist.trim().toLowerCase();
	}
}
