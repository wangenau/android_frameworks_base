package com.android.systemui.quicksettings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.FileObserver;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.statusbar.phone.QuickSettingsController;

public abstract class FileObserverTile extends QuickSettingsTile {
	protected static String TAG = FileObserverTile.class.getSimpleName();
	protected TwoStateTileRes mTileRes;
	protected CheckFunctionTile mCheckFunctionTile;
	protected boolean mFeatureEnabled;
	protected String mFilePath;

	private FileObserver mObserver;

	// keep FileObserver onEvent() callback thread safe
	private final Runnable mFileChangedRunnable = new Runnable() {
		@Override
		public void run() {
			updateEnabled();
			updateResources();
			onFileChanged(mFeatureEnabled);
			Settings.System.putInt(mContext.getContentResolver(), mTileRes.mTileSettingsString, mFeatureEnabled ? 1 : 0);
		}		
	};

	public FileObserverTile(Context context, QuickSettingsController qsc) {
		super(context, qsc);
		updateEnabled();
		mOnClick = new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				toggleState();
				updateEnabled();
				updateResources();
			}
		};
	}

	@Override
	void onPostCreate() {
		mFilePath = getFilePath();
		mObserver = new FileObserver(mFilePath, FileObserver.MODIFY) {
			@Override
			public void onEvent(int event, String file) {
				mStatusbarService.getHandler().post(mFileChangedRunnable);
			}
		};
		mObserver.startWatching();
		mTileRes = getTileRes();
		updateEnabled();
		if (mFeatureEnabled == false) {
			mFeatureEnabled = (Settings.System.getInt(mContext.getContentResolver(), mTileRes.mTileSettingsString, 0) == 1);
			setEnabled(mFeatureEnabled);
		}

		updateTile();
        super.onPostCreate();
	}

    @Override
    public void onDestroy() {
    	mObserver.stopWatching();
    	super.onDestroy();
    }

	@Override
	public void updateResources() {
		updateTile();
		super.updateResources();
	}

	protected abstract String getFilePath();

	protected abstract TwoStateTileRes getTileRes();
	
	protected abstract CheckFunctionTile getCheckFunctionTile();

	protected void setEnabled(boolean enabled) {
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter(new File(mFilePath)));
			String output = "" + (enabled ? "1" : "0");
			writer.write(output.toCharArray(), 0, output.toCharArray().length);
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private synchronized void updateTile() {
		mLabel = mContext.getString(mFeatureEnabled ? mTileRes.mTileOnLabel
				: mTileRes.mTileOffLabel);
		mDrawable = mFeatureEnabled ? mTileRes.mTileOnDrawable
				: mTileRes.mTileOffDrawable;
	}

	protected void toggleState() {
		mCheckFunctionTile = getCheckFunctionTile();

		if (mCheckFunctionTile.mTileCheckResult == true) {
			final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
			builder.setPositiveButton(com.android.internal.R.string.ok, new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					dialog.cancel();
				}
			});
			builder.setMessage(mContext.getString(mCheckFunctionTile.mTileToastText));
			builder.setTitle(null);
			builder.setCancelable(false);
			final Dialog dialog = builder.create();
			dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_TOAST);
			try {
				WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
			} catch (RemoteException e) {
			}
			mStatusbarService.animateCollapsePanels();
			dialog.show();
		} else {
			updateEnabled();
			setEnabled(!mFeatureEnabled);
			Settings.System.putInt(mContext.getContentResolver(), mTileRes.mTileSettingsString, !mFeatureEnabled ? 1 : 0);
		}
	}

	/**
	 * subclasses can override onFileChanged() to hook
	 * into the FileObserver onEvent() callback
	 */

	protected void onFileChanged(boolean featureState){}

	protected void updateEnabled() {
		mFeatureEnabled = isFeatureOn();
	}

	protected boolean isFeatureOn() {
		if (mFilePath == null || mFilePath.isEmpty()) {
			return false;
		}
		File file = new File(mFilePath);
		if (!file.exists()) {
			return false;
		}
		String content = null;
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(file));
			content = reader.readLine();
			Log.i(TAG, "isFeatureOn(): content: " + content);
			return "1".equals(content) || "Y".equalsIgnoreCase(content)
					|| "on".equalsIgnoreCase(content);
		} catch (Exception e) {
			Log.i(TAG, "exception reading feature file", e);
			return false;
		} finally {
			try {
				if (reader != null) {
					reader.close();
				}
			} catch (IOException e) {
				// ignore
			}
		}
	}

	protected static class TwoStateTileRes {
		int mTileOnLabel;
		int mTileOffLabel;
		int mTileOnDrawable;
		int mTileOffDrawable;
		String mTileSettingsString;

		public TwoStateTileRes(int labelOn, int labelOff, int drawableOn,
				int drawableOff, String SettingsString) {
			mTileOnLabel = labelOn;
			mTileOffLabel = labelOff;
			mTileOnDrawable = drawableOn;
			mTileOffDrawable = drawableOff;
			mTileSettingsString = SettingsString;
		}
	}

	protected static class CheckFunctionTile {
		boolean mTileCheckResult;
		int mTileToastText;

		public CheckFunctionTile(boolean CheckResult, int ToastText) {
			mTileCheckResult = CheckResult;
			mTileToastText = ToastText;
		}
	}
}
