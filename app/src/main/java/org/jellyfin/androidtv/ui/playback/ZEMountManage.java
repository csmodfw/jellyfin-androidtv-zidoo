package org.jellyfin.androidtv.ui.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class ZEMountManage {
	private Context				mContext				= null;
	private BroadcastReceiver	mMountStatusReceiver	= null;
	private boolean				isLockMount				= false;
	private String				mPath					= null;

	public ZEMountManage(Context context) {
		this.mContext = context;
		initMountBroadCast();
	}

	private void initMountBroadCast() {
		mMountStatusReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {

				synchronized (ZEMountManage.this) {
					mPath = intent.getStringExtra("path");
					ZEMountManage.this.notify();
				}
			}
		};
	}

	public String mountSmb(String shareName, String ip, String user, String pwd) {
		synchronized (this) {
			if (!registerReceiver()) {
				return null;
			}
			mPath = null;
			try {
				Intent intent = new Intent("com.ze.mount.action");
				intent.putExtra("type", 0);
				intent.putExtra("tag", System.currentTimeMillis() + "");
				intent.putExtra("share", shareName);
				intent.putExtra("ip", ip);
				intent.putExtra("user", user);
				intent.putExtra("pwd", pwd);// ""
				mContext.sendBroadcast(intent);
				wait(2200);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			unregisterReceiver();
			return mPath;
		}
	}

	public String mountNfs(String shareName, String ip) {
		synchronized (this) {
			if (!registerReceiver()) {
				return null;
			}
			mPath = null;
			try {
				Intent intent = new Intent("com.ze.mount.action");
				intent.putExtra("type", 1);
				intent.putExtra("tag", System.currentTimeMillis() + "");
				intent.putExtra("share", shareName);
				intent.putExtra("ip", ip);
				mContext.sendBroadcast(intent);
				wait(2200);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			unregisterReceiver();
			return mPath;
		}
	}

	private boolean registerReceiver() {
		try {
			synchronized (this) {
				if (isLockMount) {
					return false;
				}
				isLockMount = true;
				mContext.registerReceiver(mMountStatusReceiver, new IntentFilter("com.ze.mount.result.action"));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return true;
	}

	private void unregisterReceiver() {
		try {
			synchronized (this) {
				if (isLockMount) {
					mContext.unregisterReceiver(mMountStatusReceiver);
				}
				isLockMount = false;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
}
