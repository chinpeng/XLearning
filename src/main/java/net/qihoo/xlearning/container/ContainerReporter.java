package net.qihoo.xlearning.container;

import com.google.gson.Gson;
import net.qihoo.xlearning.api.ApplicationContainerProtocol;
import net.qihoo.xlearning.util.Utilities;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ResourceCalculatorProcessTree;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ContainerReporter extends Thread {
  private static final Log LOG = LogFactory.getLog(ContainerReporter.class);

  private ApplicationContainerProtocol protocol;

  private Configuration conf;

  private String gpuStr;

  private XLearningContainerId containerId;

  private String xlearningCmdProcessId;

  private String containerProcessId;

  private Class<? extends ResourceCalculatorProcessTree> processTreeClass;

  private ConcurrentHashMap<String, List> cpuMetrics;

  private ConcurrentHashMap<String, List<Long>> gpuMemoryUsed;

  private ConcurrentHashMap<String, List<Long>> gpuUtilization;


  public ContainerReporter(ApplicationContainerProtocol protocol, Configuration conf,
                           XLearningContainerId xlearningContainerId, String gpuStr, String xlearningCmdProcessId) {
    this.protocol = protocol;
    this.conf = conf;
    this.gpuStr = gpuStr;
    this.containerId = xlearningContainerId;
    this.xlearningCmdProcessId = xlearningCmdProcessId;
    this.containerProcessId = null;
    this.processTreeClass = conf.getClass(YarnConfiguration.NM_CONTAINER_MON_PROCESS_TREE, null,
        ResourceCalculatorProcessTree.class);
    this.cpuMetrics = new ConcurrentHashMap<>();
    this.gpuMemoryUsed = new ConcurrentHashMap<>();
    this.gpuUtilization = new ConcurrentHashMap<>();
  }

  public void run() {
    if (!this.gpuStr.equals("")) {
      try {
        produceGpuMetrics(this.gpuStr);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    try {
      produceCpuMetrics(this.xlearningCmdProcessId);
    } catch (IOException e) {
      e.printStackTrace();
    }

    while (true) {
      Utilities.sleep(3000);
      if (!this.gpuStr.equals("")) {
        try {
          protocol.reportGpuMemeoryUsed(containerId, new Gson().toJson(gpuMemoryUsed));
          protocol.reportGpuUtilization(containerId, new Gson().toJson(gpuUtilization));
        } catch (Exception e) {
          LOG.info("report gpu metrics exception:" + e);
        }
      }
      try {
        protocol.reportCpuMetrics(containerId, new Gson().toJson(cpuMetrics));
      } catch (Exception e) {
        LOG.debug("report cpu metrics exception:" + e);
      }
    }
  }

  private static class ProcessTreeInfo {
    private ContainerId containerId;
    private String pid;
    private ResourceCalculatorProcessTree pTree;
    private long vmemLimit;
    private long pmemLimit;
    private int cpuVcores;

    public ProcessTreeInfo(ContainerId containerId, String pid,
                           ResourceCalculatorProcessTree pTree, long vmemLimit, long pmemLimit,
                           int cpuVcores) {
      this.containerId = containerId;
      this.pid = pid;
      this.pTree = pTree;
      this.vmemLimit = vmemLimit;
      this.pmemLimit = pmemLimit;
      this.cpuVcores = cpuVcores;
    }

    public ContainerId getContainerId() {
      return this.containerId;
    }

    public String getPID() {
      return this.pid;
    }

    public void setPid(String pid) {
      this.pid = pid;
    }

    public ResourceCalculatorProcessTree getProcessTree() {
      return this.pTree;
    }

    public void setProcessTree(ResourceCalculatorProcessTree pTree) {
      this.pTree = pTree;
    }

    public long getVmemLimit() {
      return this.vmemLimit;
    }

    /**
     * @return Physical memory limit for the process tree in bytes
     */
    public long getPmemLimit() {
      return this.pmemLimit;
    }

    /**
     * Return the number of cpu vcores assigned
     *
     * @return
     */
    public int getCpuVcores() {
      return this.cpuVcores;
    }

  }

  private void produceCpuMetrics(String xlearningCmdProcessId) throws IOException {
    String command = "cat /proc/" + xlearningCmdProcessId + "/stat";
    try {
      Process process = Runtime.getRuntime().exec(command);
      InputStream is = process.getInputStream();
      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      String line;
      while ((line = br.readLine()) != null) {
        String[] strs = line.split(" ");
        this.containerProcessId = strs[3];
        break;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    LOG.info("containerProcessId is:" + this.containerProcessId);
    ProcessTreeInfo processTreeInfo =
        new ProcessTreeInfo(this.containerId.getContainerId(),
            null, null, 0, 0, 0);
    ResourceCalculatorProcessTree pt =
        ResourceCalculatorProcessTree.getResourceCalculatorProcessTree(this.containerProcessId, this.processTreeClass, conf);
    processTreeInfo.setPid(this.containerProcessId);
    processTreeInfo.setProcessTree(pt);
    final ResourceCalculatorProcessTree pTree = processTreeInfo.getProcessTree();
    final DecimalFormat df = new DecimalFormat("#.00");
    df.setRoundingMode(RoundingMode.HALF_UP);
    LOG.info("Starting thread to read cpu metrics");
    Thread cpuMetricsThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          while (true) {
            pTree.updateProcessTree();
            Long time = (new Date()).getTime();
            double currentPmemUsage = 0.0;
            currentPmemUsage = Double.parseDouble(df.format(pTree.getRssMemorySize() / 1024.0 / 1024.0 / 1024.0));
            int cpuUsagePercentPerCore = (int) pTree.getCpuUsagePercent();
            if (cpuUsagePercentPerCore < 0) {
              cpuUsagePercentPerCore = 0;
            }
            if (currentPmemUsage < 0.0) {
              currentPmemUsage = 0.0;
            }
            List memPoint = new ArrayList();
            List utilPoint = new ArrayList();
            memPoint.add(time);
            memPoint.add(currentPmemUsage);
            utilPoint.add(time);
            utilPoint.add(cpuUsagePercentPerCore);
            cpuMetrics.put("CPUMEM", memPoint);
            cpuMetrics.put("CPUUTIL", utilPoint);
            Utilities.sleep(1000);
          }
        } catch (Exception e) {
          LOG.warn("Exception in thread read cpu metrics");
          e.printStackTrace();
        }
      }
    });
    cpuMetricsThread.start();
  }

  private void produceGpuMetrics(String gpuStr) throws IOException {
    String command = "nvidia-smi --format=csv,noheader,nounits --query-gpu=index,memory.used,utilization.gpu -l 1";
    final String[] gpuList = StringUtils.split(gpuStr, ',');
    final Process finalProcess = Runtime.getRuntime().exec(command);
    LOG.info("Starting thread to redirect stdout of nvidia-smi process");
    Thread stdoutRedirectThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          BufferedReader reader;
          reader = new BufferedReader(new InputStreamReader(finalProcess.getInputStream()));
          String line;
          int i;
          while ((line = reader.readLine()) != null) {
            Long time = (new Date()).getTime();
            String[] gpusIndex = StringUtils.split(line, ',');
            for (i = 0; i < gpuList.length; i++) {
              if (i == Integer.valueOf(gpusIndex[0])) {
                List<Long> memPoint = new ArrayList<>();
                memPoint.add(time);
                memPoint.add(Long.parseLong(gpusIndex[1].trim()));
                List<Long> utilPoint = new ArrayList<>();
                utilPoint.add(time);
                utilPoint.add(Long.parseLong(gpusIndex[2].trim()));
                gpuMemoryUsed.put(gpuList[i], memPoint);
                gpuUtilization.put(gpuList[i], utilPoint);
                break;
              }
            }
          }
        } catch (Exception e) {
          LOG.warn("Exception in thread nvidia-smi stdoutRedirectThread");
          e.printStackTrace();
        }
      }
    });
    stdoutRedirectThread.start();
  }

}
