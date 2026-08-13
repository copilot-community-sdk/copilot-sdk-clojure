(ns github.copilot-sdk.bench.diagnostics
  (:import [java.lang.management ManagementFactory]
           [java.time Instant]))

(def ^:private operating-system-bean
  (ManagementFactory/getOperatingSystemMXBean))

(def ^:private memory-bean
  (ManagementFactory/getMemoryMXBean))

(def ^:private garbage-collector-beans
  (vec (ManagementFactory/getGarbageCollectorMXBeans)))

(def ^:private compilation-bean
  (ManagementFactory/getCompilationMXBean))

(defn- supported-sum
  [values]
  (when (every? #(not (neg? %)) values)
    (reduce + values)))

(defn- process-cpu-ms
  []
  (when (instance? com.sun.management.OperatingSystemMXBean
                   operating-system-bean)
    (/ (double
        (.getProcessCpuTime
         ^com.sun.management.OperatingSystemMXBean operating-system-bean))
       1000000.0)))

(defn- system-load-average
  []
  (let [load (.getSystemLoadAverage operating-system-bean)]
    (when-not (neg? load)
      load)))

(defn- jit-compilation-time-ms
  []
  (when (and compilation-bean
             (.isCompilationTimeMonitoringSupported compilation-bean))
    (double (.getTotalCompilationTime compilation-bean))))

(defn capture
  [{:keys [run-id implementation workload replicate pair-order-index
           workload-order-index checkpoint window-index timed-operation-count
           validation-operation-count started-ns rss-bytes]}]
  (let [heap (.getHeapMemoryUsage memory-bean)]
    {:schema-version 1
     :run-id run-id
     :implementation implementation
     :workload workload
     :replicate replicate
     :pair-order-index pair-order-index
     :workload-order-index workload-order-index
     :checkpoint checkpoint
     :window-index window-index
     :timed-operation-count timed-operation-count
     :validation-operation-count validation-operation-count
     :wall-time (.toString (Instant/now))
     :elapsed-ms (/ (double (- (System/nanoTime) started-ns)) 1000000.0)
     :process-cpu-ms (process-cpu-ms)
     :rss-bytes rss-bytes
     :heap-used-bytes (.getUsed heap)
     :heap-committed-bytes (.getCommitted heap)
     :gc-count (supported-sum
                (map #(.getCollectionCount %) garbage-collector-beans))
     :gc-time-ms (some-> (supported-sum
                          (map #(.getCollectionTime %) garbage-collector-beans))
                         double)
     :jit-code-bytes nil
     :jit-compilation-time-ms (jit-compilation-time-ms)
     :event-loop-idle-ms nil
     :total-allocated-bytes nil
     :host-load-average-1m (system-load-average)
     :available-processors (.availableProcessors (Runtime/getRuntime))}))
