/*
 *  Copyright (C) 2015 GuDong <gudong.name@gmail.com>
 *
 *  This file is part of GdTranslate
 *
 *  GdTranslate is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  GdTranslate is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with GdTranslate.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package name.gudong.translate.mvp.presenters;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;

import com.litesuits.orm.LiteOrm;
import com.orhanobut.logger.Logger;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import me.gudong.translate.BuildConfig;
import me.gudong.translate.R;
import name.gudong.translate.GDApplication;
import name.gudong.translate.listener.clipboard.ClipboardManagerCompat;
import name.gudong.translate.mvp.model.DownloadService;
import name.gudong.translate.mvp.model.WarpAipService;
import name.gudong.translate.mvp.model.entity.AbsResult;
import name.gudong.translate.mvp.model.entity.Result;
import name.gudong.translate.mvp.model.type.EIntervalTipTime;
import name.gudong.translate.mvp.views.IClipboardService;
import name.gudong.translate.ui.activitys.MainActivity;
import name.gudong.translate.util.SpUtils;
import name.gudong.translate.util.StringUtils;
import name.gudong.translate.util.Utils;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.schedulers.Schedulers;


/**
 * Created by GuDong on 2/28/16 20:48.
 * Contact with gudong.name@gmail.com.
 */
public class ClipboardPresenter extends BasePresenter<IClipboardService> {
    private static final String KEY_TAG = "clipboard";
    /**
     * 首先说明一个情况，这里的系统粘贴板在监听到粘贴板内容发生变化时
     * 对应的监听方法会被执行多次 也就是说用户的复制操作 会引起这里发送多次数据请求
     * 这是不合理的，所以设置一个缓存用于解决c重复请求问题
     */

    /**
     * 定义一个查询集合，用于缓存当前的查询队列，当队列中存在已经复制的关键字就不会继续去发起查询操作了
     * 注意要在合适的时候清空它
     */
    private List<String> listQuery = new ArrayList<>();

    /**
     * 记录不同 TipView 原始 Result -> 本地 Result 映射
     * 当初始化界面时，会拿网络返回的 Result 去做本地查询，看他有没有本地的收藏，如果有，就把他加入这这个 map
     * 键为原始 Result 值为 本地 Result  可能为空
     */
    private Map<Result,Result> mMapResult = new WeakHashMap<>();

    @Inject
    ClipboardManagerCompat mClipboardWatcher;

    private ClipboardManagerCompat.OnPrimaryClipChangedListener mListener = () -> performClipboardCheck();

    /**
     * 定时显示 Tip 事件源
     */
    Subscription mSubscription;
    /**
     * 显示 Tip 的动作
     */
    Action1 mActionShowTip;


    @Inject
    public ClipboardPresenter(LiteOrm liteOrm, WarpAipService apiService, DownloadService downloadService, Service service) {
        super(liteOrm, apiService,downloadService, service);
    }

    @Override
    public void onCreate(){
        super.onCreate();
        initCountdownSetting();
        showNormalNotification(getContext());
    }

    private void initCountdownSetting(){
        mActionShowTip = (t)->{
            Result result = getResult();
            if(result == null)return;
            mView.showResult(getResult(),false);
        };
    }

    private void showNormalNotification(Context context) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
        builder.setContentTitle(context.getString(R.string.app_name));
        builder.setContentText("点击打开咕咚翻译");
        if(Utils.isSDKHigh5()){
            builder.setSmallIcon(R.drawable.icon_notification);
            builder.setColor(Color.rgb(121,85,72));
        }else{
            builder.setSmallIcon(R.mipmap.ic_launcher);
        }
        builder.setPriority(NotificationCompat.PRIORITY_MIN);
        builder.setOngoing(true);

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClass(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        PendingIntent contextIntent = PendingIntent.getActivity(context, 0, intent, 0);
        builder.setContentIntent(contextIntent);

        long[] vibrate = {0, 50, 0, 0};
        builder.setVibrate(vibrate);

        Notification notification = builder.build();
        NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        int NOTIFY_ID = 524947901;
        mNotificationManager.notify(NOTIFY_ID, notification);
    }

    /**
     * 根据用户设置 循环显示生词本中的内容 逻辑写的稍负责
     */
    public void controlShowTipCyclic(){
        EIntervalTipTime tipTime = SpUtils.getIntervalTimeWay(GDApplication.mContext);
        int time = tipTime.getIntervalTime();

        boolean reciteFlag = SpUtils.getReciteOpenOrNot(mService);
        //用户设置了开启背单词 或者 时间隔时间变化了 下面的判断代码写的有点复杂
        //但是这是错了好多次，试出来可以成功运行的代码，尼玛，多条件动态配置选项死去活来啊 ~
        if((mSubscription == null && reciteFlag) || (mSubscription != null && reciteFlag && !mSubscription.isUnsubscribed())){
            if(mSubscription != null && !mSubscription.isUnsubscribed()){
                mSubscription.unsubscribe();
            }
            Logger.i(KEY_TAG,"用户设置了开启背单词 此时实例化 mSubscription 也可能是时间间隔值变化了 time is "+time);
            mSubscription = Observable.interval(time, TimeUnit.MINUTES)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(mActionShowTip);
        }

        //外界关闭 背单词 功能 并设置 mSubscription null
        if(mSubscription != null && !reciteFlag && !mSubscription.isUnsubscribed()){
            mSubscription.unsubscribe();
            mSubscription = null;
            Logger.i(KEY_TAG,"用户关闭背单词");
        }
    }

    public void search(final String content) {
        mWarpApiService.translate(SpUtils.getTranslateEngineWay(mService), content)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .filter((result)->{return result.wrapErrorCode() == 0;})
                .subscribe(new Subscriber<AbsResult>() {
                    @Override
                    public void onCompleted() {
                        //清空缓存
                        listQuery.clear();
                    }

                    @Override
                    public void onError(Throwable e) {
                        if(e instanceof SocketTimeoutException){
                            mView.errorPoint("网络请求超时，请稍后重试。");
                        }else{
                            if(BuildConfig.DEBUG){
                                mView.errorPoint("请求数据异常，您可以试试切换其他引擎。"+e.getMessage());
                                e.printStackTrace();
                            }else{
                                mView.errorPoint("请求数据异常，您可以试试切换其他引擎。");
                            }
                        }
                    }

                    @Override
                    public void onNext(AbsResult result) {
                        mView.showResult(result.getResult(),true);
                    }
                });
    }

    /**
     * 添加粘贴板变化监听方法
     */
    public void addListener() {
        //添加粘贴板变化监听方法
        mClipboardWatcher.addPrimaryClipChangedListener(mListener);
    }

    public void initFavoriteStatus(Result result){
        Result localResult= isFavorite(result.getQuery());
        if(localResult!=null){
            mView.initWithFavorite(result);
        }else{
            mView.initWithNotFavorite(result);
        }
    }

    public void clickFavorite(Result result){
        Result localResult= isFavorite(result.getQuery());
        if (localResult!=null) {
            int index = deleteResultFromDb(localResult);
            if (index > 0) {
                mView.initWithNotFavorite(result);
                Logger.i("删除成功");
            } else {
                Logger.i("删除失败");
            }
        }else{
            long index = insertResultToDb(result);
            if (index > 0) {
                mView.initWithFavorite(result);
                Logger.i("插入成功");
            } else {
                Logger.i("插入失败");
            }
        }
    }

    private void performClipboardCheck() {
        CharSequence content = mClipboardWatcher.getText();

        //处理缓存 因为粘贴板的回调操作可能触发多次
        String query = content.toString();
        Logger.i("粘贴板的单词为 "+query);
        if (listQuery.contains(query)) return;
        listQuery.add(query);

        //只有用户在打开了 划词翻译的情况下 划词翻译才能正常工作
        if(!SpUtils.getOpenJITOrNot(mService))return;

        //如果当前界面是 咕咚翻译的主界面 那么也不对粘贴板做监听( Debug 时开启)
        if(!BuildConfig.DEBUG){
            if(SpUtils.getAppFront(mService))return;
        }

        // 检查粘贴板的内容是不是单词 以及是不是为空
        if(!checkInput(content.toString()))return;

        //查询数据
        search(query);
    }

    private boolean checkInput(String input){
        // empty check
        if (TextUtils.isEmpty(input)) {
            Logger.e("剪贴板为空了");
            return false;
        }

        if(StringUtils.isChinese(input)){
            Logger.e(input+" 中包含中文字符");
            return false;
        }

        if(StringUtils.isValidEmailAddress(input)){
            Logger.e(input+" 是一个邮箱");
            return false;
        }

        if(StringUtils.isValidUrl(input)){
            Logger.e(input+" 是一个网址");
            return false;
        }

        if(StringUtils.isValidNumeric(input)){
            Logger.e(input+" 是一串数字");
            return false;
        }

        // length check
        if(StringUtils.isMoreThanOneWord(input)){
            mView.errorPoint("咕咚翻译目前不支持划句或者划短语翻译\n多谢理解");
            return false;
        }

        return true;
    }

    public void onDestroy() {
        super.onDestroy();
        mClipboardWatcher.removePrimaryClipChangedListener(mListener);
    }


    private Result getResult(){
        List<Result> results = mLiteOrm.query(Result.class);
        if(results.isEmpty()){
            return null;
        }
        int index = new Random().nextInt(results.size());
        return results.get(index);
    }
}