package com.stockholm.fota.engine;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import com.adups.iot_libs.OtaAgentPolicy;
import com.adups.iot_libs.constant.Error;
import com.adups.iot_libs.info.VersionInfo;
import com.adups.iot_libs.inter.ICheckVersionCallback;
import com.adups.iot_libs.inter.IDownloadListener;
import com.adups.iot_libs.utils.SPFTool;
import com.stockholm.common.IntentExtraKey;
import com.stockholm.common.utils.StockholmLogger;
import com.stockholm.fota.policy.PolicyManager;

public final class UpdateEngine {
    public static PolicyManager policyInter = new PolicyManager();
    private static final String TAG = "UpdateEngine";

    private static UpdateEngine mInstance;

    //同时接收多个OTA任务但只执行一个
    private boolean processing = false;
    //异步改同步
    private Object lock = new Object();

    private Context context;
    private UpdateCallback updateCallback;

    private UpdateEngine(Context context) {
        this.context = context;
    }

    public static UpdateEngine getInstance(Context context) {
        if (mInstance == null) {
            synchronized (VersionInfo.class) {
                mInstance = new UpdateEngine(context);
            }
        }
        return mInstance;
    }

    public void silenceUpdateExecute() {
        if (processing) {
            StockholmLogger.d(TAG, "fota task is in processing ");
            return;
        }
        SilenceTask silenceTask = new SilenceTask();
        silenceTask.execute();
    }

    public void setUpdateCallback(UpdateCallback updateCallback) {
        this.updateCallback = updateCallback;
    }

    public void saveCheckInfo() {

        //检测周期策略
        int checkCycle = policyInter.getCheckCycle();
        StockholmLogger.d(TAG, "fota checkCycle got: " + checkCycle);
        if (checkCycle > 0) {
            //开启周期检测任务(优先以后台下发的检测周期为周期)
            SPFTool.putLong(AlarmService.ALARM_CHECK_VERSION_CYCLE, checkCycle);
            AlarmService.startAlarmService(context);
        }

    }

    //异步改同步
    private void lock() {
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void lockNotify() {
        synchronized (lock) {
            lock.notify();
        }
    }

    public interface UpdateCallback {
        void onCheckUpdateFail();
    }

    private class SilenceTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected void onPreExecute() {
            processing = true;
            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            otaTask();
            lock();
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            processing = false;
            super.onPostExecute(aVoid);
        }

        private void otaTask() {
            OtaAgentPolicy.checkVersion(new ICheckVersionCallback() {
                @Override
                public void onCheckSuccess(VersionInfo versionInfo) {
                    StockholmLogger.d(TAG, "check fota success ,version info: " + versionInfo.toString());
                    saveCheckInfo();
                    download();
                }

                @Override
                public void onCheckFail(int i) {
                    StockholmLogger.d(TAG, "check fota error,error info: " + Error.getErrorMessage(i));
                    lockNotify();
                    updateCallback.onCheckUpdateFail();
                }
            });
        }

        private void download() {
            StockholmLogger.d(TAG, "fota send ota view broadcast ");
            Intent intent1 = new Intent();
            intent1.setAction(IntentExtraKey.ACTION_SHOW_OTA_VIEW);
            context.sendBroadcast(intent1);
            Intent intent2 = new Intent();
            intent2.setAction(IntentExtraKey.ACTION_DISMISS_AUTO_DISPLAY);
            context.sendBroadcast(intent2);
            OtaAgentPolicy.download(new IDownloadListener() {
                @Override
                public void onPrepare() {
                    StockholmLogger.d(TAG, "fota download on prepare ");
                }

                @Override
                public void onDownloadProgress(long l, long l1) {
                }

                @Override
                public void onFailed(int i) {
                    Intent intent = new Intent();
                    intent.setAction(IntentExtraKey.ACTION_CLEAR_OTA_VIEW);
                    context.sendBroadcast(intent);
                    StockholmLogger.d(TAG, "fota download on error,error info: " + Error.getErrorMessage(i));
                    lockNotify();
                }

                @Override
                public void onCompleted() {
                    StockholmLogger.d(TAG, "fota download complete");
                    rebootUpdate();
                }

                @Override
                public void onCancel() {
                    StockholmLogger.d(TAG, "fota download cancel");
                    lockNotify();
                }
            });
        }

        private void rebootUpdate() {
            StockholmLogger.d(TAG, "reboot to upgrade");
            OtaAgentPolicy.rebootUpgrade((i, s) -> lockNotify());
        }
    }

}
