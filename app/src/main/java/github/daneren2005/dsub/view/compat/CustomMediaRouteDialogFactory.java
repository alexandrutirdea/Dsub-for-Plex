package github.daneren2005.dsub.view.compat;

import androidx.mediarouter.app.MediaRouteChooserDialogFragment;
import androidx.mediarouter.app.MediaRouteControllerDialogFragment;
import androidx.mediarouter.app.MediaRouteDialogFactory;

import github.daneren2005.dsub.view.RouteControllerSheet;
import github.daneren2005.dsub.view.RoutePickerSheet;

public class CustomMediaRouteDialogFactory extends MediaRouteDialogFactory {
	@Override
	public MediaRouteChooserDialogFragment onCreateChooserDialogFragment() {
		return new RoutePickerSheet();
	}

	@Override
	public MediaRouteControllerDialogFragment onCreateControllerDialogFragment() {
		return new RouteControllerSheet();
	}
}
