/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.leakcanary;

import android.app.Application;
import android.os.StrictMode;
import com.squareup.leakcanary.LeakCanary;

public class ExampleApplication extends Application {
  @Override public void onCreate() {
    super.onCreate();
    setupLeakCanary();
  }

  /**
   * 1.开启严格模式
   * 2.检测当前进程是否是HeapAnalyzerService所处在的进程，是则程序结束（HeapAnalyzerService用于heap分析）
   * 3.LeakCanary.install
   */
  protected void setupLeakCanary() {
    enabledStrictMode();
    if (LeakCanary.isInAnalyzerProcess(this)) {
      // This process is dedicated to LeakCanary for heap analysis.
      // You should not init your app in this process.
      return;
    }
    LeakCanary.install(this);
  }

  /**
   * StrictMode 严格模式 （该模式仅能在开发阶段使用，不可用于线上环境）。
   * StrictMode 用于在开发阶段帮助开发者检测出一些不规范的代码，帮助
   * 开发者优化和改善代码逻辑，以达到提升应用响应能力的目的。
   *
   * StrictMode支持线程策略(ThreadPolicy)和VM策略(VmPolicy)
   * 线程策略检测的内容有:a.自定义的耗时调用 使用detectCustomSlowCalls()开启
   *                      b.磁盘读取操作 使用detectDiskReads()开启
   *                      c.磁盘写入操作 使用detectDiskWrites()开启
   *                      d.网络操作 使用detectNetwork()开启
   * VmPolicy虚拟机检测的内容有:a.Activity泄露 使用detectActivityLeaks()开启
   *                            b.未关闭的Closable对象泄露 使用detectLeakedClosableObjects()开启
   *                            c.泄露的Sqlite对象 使用detectLeakedSqlLiteObjects()开启
   *                            d.检测实例数量 使用setClassInstanceLimit()开启
   *
   *  https://www.cnblogs.com/yaowen/p/6024690.html
   */
  private static void enabledStrictMode() {
    StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder() //
        .detectAll() //detect，翻译是侦察
        .penaltyLog() //penalty，翻译是刑罚
        .penaltyDeath() //
        .build());
  }
}
