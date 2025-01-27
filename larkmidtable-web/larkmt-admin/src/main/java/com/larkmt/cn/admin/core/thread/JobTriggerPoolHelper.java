package com.larkmt.cn.admin.core.thread;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.larkmt.cn.admin.core.conf.ExcecutorConfig;
import com.larkmt.cn.admin.core.conf.JobAdminConfig;
import com.larkmt.cn.admin.core.trigger.JobTrigger;
import com.larkmt.cn.admin.core.trigger.TriggerTypeEnum;
import com.larkmt.cn.admin.entity.JobInfo;
import com.larkmt.cn.admin.entity.JobLog;
import com.larkmt.core.biz.model.ReturnT;
import com.larkmt.core.log.JobLogger;
import com.larkmt.core.util.Constants;
import com.larkmt.core.util.ProcessUtil;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * job trigger thread pool helper
 *
 * @author xuxueli 2018-07-03 21:08:07
 */
public class JobTriggerPoolHelper {
  private static Logger logger = LoggerFactory.getLogger(JobTriggerPoolHelper.class);

  // ---------------------- trigger pool ----------------------

  // fast/slow thread pool
  private ThreadPoolExecutor fastTriggerPool = null;
  private ThreadPoolExecutor slowTriggerPool = null;

  public void start() {
    fastTriggerPool =
        new ThreadPoolExecutor(
            10,
            JobAdminConfig.getAdminConfig().getTriggerPoolFastMax(),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(1000),
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                return new Thread(
                    r, "LarkMidTable, admin JobTriggerPoolHelper-fastTriggerPool-" + r.hashCode());
              }
            });

    slowTriggerPool =
        new ThreadPoolExecutor(
            10,
            JobAdminConfig.getAdminConfig().getTriggerPoolSlowMax(),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(2000),
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                return new Thread(
                    r, "LarkMidTable, admin JobTriggerPoolHelper-slowTriggerPool-" + r.hashCode());
              }
            });
  }

  public void stop() {
    // triggerPool.shutdown();
    fastTriggerPool.shutdownNow();
    slowTriggerPool.shutdownNow();
    logger.info(">>>>>>>>> LarkMidTable trigger thread pool shutdown success.");
  }

  // job timeout count
  private volatile long minTim = System.currentTimeMillis() / 60000; // ms > min
  private volatile ConcurrentMap<Integer, AtomicInteger> jobTimeoutCountMap =
      new ConcurrentHashMap<>();

  /** add trigger */
  public void addTrigger(
      final int jobId,
      final TriggerTypeEnum triggerType,
      final int failRetryCount,
      final String executorShardingParam,
      final String executorParam) {

    // choose thread pool
    ThreadPoolExecutor triggerPool_ = fastTriggerPool;
    AtomicInteger jobTimeoutCount = jobTimeoutCountMap.get(jobId);
    if (jobTimeoutCount != null && jobTimeoutCount.get() > 10) { // job-timeout 10 times in 1 min
      triggerPool_ = slowTriggerPool;
    }
    // trigger
    triggerPool_.execute(
        () -> {
          long start = System.currentTimeMillis();
          try {
            // do trigger
            JobTrigger.trigger(
                jobId, triggerType, failRetryCount, executorShardingParam, executorParam);
          } catch (Exception e) {
            logger.error(e.getMessage(), e);
          } finally {
            // check timeout-count-map
            long minTim_now = System.currentTimeMillis() / 60000;
            if (minTim != minTim_now) {
              minTim = minTim_now;
              jobTimeoutCountMap.clear();
            }
            // incr timeout-count-map
            long cost = System.currentTimeMillis() - start;
            if (cost > 500) { // ob-timeout threshold 500ms
              AtomicInteger timeoutCount =
                  jobTimeoutCountMap.putIfAbsent(jobId, new AtomicInteger(1));
              if (timeoutCount != null) {
                timeoutCount.incrementAndGet();
              }
            }
          }
        });
  }

  // ---------------------- helper ----------------------

  private static JobTriggerPoolHelper helper = new JobTriggerPoolHelper();

  public static void toStart() {
    helper.start();
  }

  public static void toStop() {
    helper.stop();
  }

  /**
   * @param jobId
   * @param triggerType
   * @param failRetryCount >=0: use this param <0: use param from job info config
   * @param executorShardingParam
   * @param executorParam null: use job param not null: cover job param
   */
  public static void trigger(
      int jobId,
      TriggerTypeEnum triggerType,
      int failRetryCount,
      String executorShardingParam,
      String executorParam) {
    helper.addTrigger(jobId, triggerType, failRetryCount, executorShardingParam, executorParam);
  }

  public static String[] buildFlinkXExecutorCmd(
      String flinkXShPath, String tmpFilePath, int jobId) {
    long timestamp = System.currentTimeMillis();
    List<String> cmdArr = new ArrayList<>();
    if (JobTriggerPoolHelper.isWindows()) {
      cmdArr.add(Constants.CMDWINDOW);
      cmdArr.add(flinkXShPath);
      cmdArr.add(tmpFilePath);
    } else {
      cmdArr.add(Constants.CMDLINUX);
      cmdArr.add(flinkXShPath);
      cmdArr.add(tmpFilePath);
    }
    String logHome = ExcecutorConfig.getExcecutorConfig().getFlinkxlogHome();
    File folder = new File(logHome);
    if (!folder.exists() && !folder.isDirectory()) {
      folder.mkdirs();
    }
    cmdArr.add(logHome + "/" + jobId + "" + timestamp + ".out");
    logger.info(cmdArr + " " + flinkXShPath + " " + tmpFilePath);
    return cmdArr.toArray(new String[cmdArr.size()]);
  }

  public static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("windows");
  }

  public static void runJob(int jobId) {
    try {
      JobInfo jobInfo = JobAdminConfig.getAdminConfig().getJobInfoMapper().loadById(jobId);
      StringBuilder cmdstr = new StringBuilder();
      String tmpFilePath = "";
      String[] cmdarrayFinal = null;
      tmpFilePath = generateTemJsonFile(jobInfo.getJobJson());
      cmdarrayFinal =
          buildFlinkXExecutorCmd(
              ExcecutorConfig.getExcecutorConfig().getFlinkxHome(), tmpFilePath, jobId);
      for (int j = 0; j < cmdarrayFinal.length; j++) {
        cmdstr.append(cmdarrayFinal[j] + " ");
      }
      final Process process = Runtime.getRuntime().exec(cmdstr.toString());
      String prcsId = ProcessUtil.getProcessId(process);
      JobLogger.log("Execute: " + cmdstr);
      JobLogger.log("process id: " + prcsId);
      if (FileUtil.exist(tmpFilePath)) {
        //				FileUtil.del(new File(tmpFilePath));
      }
      // 记录日志
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(new Date());
      calendar.set(Calendar.MILLISECOND, 0);
      Date triggerTime = calendar.getTime();
      JobLog jobLog = new JobLog();
      jobLog.setJobGroup(jobInfo.getJobGroup());
      jobLog.setJobId(jobInfo.getId());
      jobLog.setTriggerTime(triggerTime);
      jobLog.setJobDesc(jobInfo.getJobDesc());
      jobLog.setHandleTime(triggerTime);
      jobLog.setTriggerCode(ReturnT.SUCCESS_CODE);
      jobLog.setHandleCode(0);
      jobLog.setProcessId(prcsId);
      // 设置job的执行路径
      jobLog.setExecutorAddress(cmdarrayFinal[3]);
      JobAdminConfig.getAdminConfig().getJobLogMapper().save(jobLog);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static String generateTemJsonFile(String jobJson) {
    String jsonPath = "";
    jsonPath = ExcecutorConfig.getExcecutorConfig().getFlinkxjsonPath();
    if (!FileUtil.exist(jsonPath)) {
      FileUtil.mkdir(jsonPath);
    }
    String tmpFilePath = jsonPath + "jobTmp-" + IdUtil.simpleUUID() + ".json";
    // jobJSON进行替换操作
    // 根据json写入到临时本地文件
    try (PrintWriter writer = new PrintWriter(tmpFilePath, "UTF-8")) {
      writer.println(jobJson);
    } catch (FileNotFoundException | UnsupportedEncodingException e) {
      JobLogger.log("JSON 临时文件写入异常：" + e.getMessage());
    }
    return tmpFilePath;
  }
}
