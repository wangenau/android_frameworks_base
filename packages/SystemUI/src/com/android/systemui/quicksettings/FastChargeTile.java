package com.android.systemui.quicksettings;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;

public class FastChargeTile extends FileObserverTile {
	public static final String TAG = FastChargeTile.class.getSimpleName();
    public static final String FFC_PATH = "/sys/kernel/fast_charge/force_fast_charge";
    private Context mContext;

	public FastChargeTile(Context context, QuickSettingsController qsc) {
		super(context, qsc);
		mContext = context;
	}

	@Override
	protected String getFilePath() {
		return FFC_PATH;
	}

	@Override
	protected TwoStateTileRes getTileRes() {
		return new TwoStateTileRes(R.string.quick_settings_fcharge_on_label
				,R.string.quick_settings_fcharge_off_label
				,R.drawable.ic_qs_fcharge_on
				,R.drawable.ic_qs_fcharge_off
				,Settings.System.FCHARGE_ENABLED);
	}

	@Override
	protected CheckFunctionTile getCheckFunctionTile() {
		Intent intent = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		boolean USBIsPlugged = (plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB);

		return new CheckFunctionTile(USBIsPlugged
				,R.string.quick_settings_fcharge_usbwarning_label);
	}

	protected void onFileChanged(boolean featureState) {
		Log.i(TAG, FFC_PATH + " changed. Notify interested parties");
	}
}
