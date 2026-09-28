package github.daneren2005.dsub.util.compat;

import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.service.DownloadFile;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import androidx.mediarouter.media.MediaRouter;

import java.util.List;

public abstract class RemoteControlClientBase {
	
	public static RemoteControlClientBase createInstance() {
		return new RemoteControlClientLP();
	}
	
	protected RemoteControlClientBase() {
		// Avoid instantiation
	}
	
	public abstract void register(final Context context, final ComponentName mediaButtonReceiverComponent);
	public abstract void unregister(final Context context);
	public abstract void setPlaybackState(int state, int index, int queueSize);
	public abstract void updateMetadata(Context context, MusicDirectory.Entry currentSong);
	public abstract void metadataChanged(MusicDirectory.Entry currentSong);
	public abstract void updateAlbumArt(MusicDirectory.Entry currentSong, Bitmap bitmap);
	public abstract void registerRoute(MediaRouter router);
	public abstract void unregisterRoute(MediaRouter router);
	public abstract void updatePlaylist(List<DownloadFile> playlist);
}
