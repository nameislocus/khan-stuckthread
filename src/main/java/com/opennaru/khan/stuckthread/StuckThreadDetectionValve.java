/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.opennaru.khan.stuckthread;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.servlet.ServletException;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.catalina.valves.ValveBase;
import org.jboss.logging.Logger;
import org.jboss.servlet.http.HttpEvent;

import com.opennaru.khan.stuckthread.management.ObjectNameUtil;
import com.opennaru.khan.stuckthread.management.StuckMonitorMBean;
import com.opennaru.khan.stuckthread.management.StuckMonitorMBeanImpl;

/**
 * This valve allows to detect requests that take a long time to process, which might
 * indicate that the thread that is processing it is stuck.
 * Based on code proposed by TomLu in Bugzilla entry #50306
 * Update for hogging thread monitoring and MBean(nameislocus)
 *
 * @author slaurent
 * @author Junshik Jeon(nameislocus@gmail.com, service@opennaru.com)
 */
public class StuckThreadDetectionValve extends ValveBase  implements Lifecycle {

    /**
     * Logger
     */
    private static Logger log = Logger.getLogger(StuckThreadDetectionValve.class);

    private final LifecycleSupport support = new LifecycleSupport(this);
    
    /**
     * The string manager for this package.
     */
//    private static final StringManager sm =
//        StringManager.getManager(Constants.Package);

    /**
     * Keeps count of the number of stuck threads detected
     */
    private final AtomicInteger stuckCount = new AtomicInteger(0);

    private final AtomicInteger hoggingCount = new AtomicInteger(0);

    /**
     * In seconds. Default 600 (10 minutes).
     */
    private int stuckThreshold = 600;

    private int hoggingThreshold = 60;

    private static StuckMonitorMBean stuckMonitor = null;
    
    /**
     * The only references we keep to actual running Thread objects are in
     * this Map (which is automatically cleaned in invoke()s finally clause).
     * That way, Threads can be GC'ed, eventhough the Valve still thinks they
     * are stuck (caused by a long monitor interval)
     */
    private final ConcurrentHashMap<Long, MonitoredThread> activeThreads =
            new ConcurrentHashMap<Long, MonitoredThread>();
    /**
     *
     */
    private final Queue<CompletedStuckThread> completedStuckThreadsQueue =
            new ConcurrentLinkedQueue<CompletedStuckThread>();

    
    
	/**
     * Specify the threshold (in seconds) used when checking for stuck threads.
     * If &lt;=0, the detection is disabled. The default is 600 seconds.
     *
     * @param stuckThreshold
     *            The new threshold in seconds
     */
    public void setStuckThreshold(int stuckThreshold) {
        this.stuckThreshold = stuckThreshold;
    }

    /**
     * @see #setThreshold(int)
     * @return The current threshold in seconds
     */
    public int getStuckThreshold() {
        return stuckThreshold;
    }

    public void setHoggingThreshold(int hoggingThreshold) {
        this.hoggingThreshold = hoggingThreshold;
    }

    /**
     * @see #setThreshold(int)
     * @return The current threshold in seconds
     */
    public int getHoggingThreshold() {
        return hoggingThreshold;
    }
    

    private void notifyStuckThreadDetected(MonitoredThread monitoredThread,
        long activeTime, int numStuckThreads) {
        StringBuffer msg = new StringBuffer();
        msg.append("stuckThreadDetectionValve.notifyStuckThreadDetected\n");
        msg.append("ThreadName=");
        msg.append(monitoredThread.getThread().getName() + "\n");
        msg.append("activeTime=");
        msg.append(Long.valueOf(activeTime) + "\n");
        msg.append("startTime=");
        msg.append(monitoredThread.getStartTime() + "\n");
        
        msg.append("numStuckThreads=");
        msg.append(Integer.valueOf(numStuckThreads) + "\n");

        msg.append("requestURI=");
        msg.append(monitoredThread.getRequestUri() + "\n");
        msg.append("stuckThreshold=");
        msg.append(Integer.valueOf(stuckThreshold) + "\n");
               
        // msg += "\n" + getStackTraceAsString(trace);
        Throwable th = new Throwable();
        th.setStackTrace(monitoredThread.getThread().getStackTrace());
        log.warn(msg.toString(), th);
    }

    private void notifyStuckThreadCompleted(String threadName,
            long activeTime, int numStuckThreads) {
    	
    	StringBuffer msg = new StringBuffer();
    	msg.append("stuckThreadDetectionValve.notifyStuckThreadCompleted\n");
    	msg.append("threadName=" + threadName + "\n");
    	msg.append("activeTime=" + Long.valueOf(activeTime) + "\n");
    	msg.append("numStuckThreads=" + Integer.valueOf(numStuckThreads));

        // Since the "stuck thread notification" is warn, this should also
        // be warn
        log.warn(msg.toString());
    }
   
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke(Request request, Response response)
            throws IOException, ServletException {

        if (stuckThreshold <= 0) {
            // short-circuit if not monitoring stuck threads
            getNext().invoke(request, response);
            return;
        }

        // Save the thread/runnable
        // Keeping a reference to the thread object here does not prevent
        // GC'ing, as the reference is removed from the Map in the finally clause

        Long key = Long.valueOf(Thread.currentThread().getId());
        StringBuffer requestUrl = request.getRequestURL();
        if(request.getQueryString()!=null) {
            requestUrl.append("?");
            requestUrl.append(request.getQueryString());
        }
        MonitoredThread monitoredThread = new MonitoredThread(Thread.currentThread(),
            requestUrl.toString());
        activeThreads.put(key, monitoredThread);
        log.debug( "invoke/activeThread.put/key=" + key);
        
        try {
            getNext().invoke(request, response);
        } finally {
            activeThreads.remove(key);
            log.debug( "invoke/activeThread.remove/key=" + key);

            if (monitoredThread.markAsDone() == MonitoredThreadState.STUCK ||
            	monitoredThread.markAsDone() == MonitoredThreadState.HOGGING) {
            	log.debug("invoke/add completed queue");
                completedStuckThreadsQueue.add(
                        new CompletedStuckThread(monitoredThread.getThread().getName(),
                            monitoredThread.getActiveTimeInMillis()));
            }
        }
    }

    @Override
    public void event(Request request, Response response, HttpEvent event)
            throws IOException, ServletException {

        if (stuckThreshold <= 0) {
            // short-circuit if not monitoring stuck threads
            getNext().event(request, response, event);
            return;
        }
        
        // Save the thread/runnable
        // Keeping a reference to the thread object here does not prevent
        // GC'ing, as the reference is removed from the Map in the finally clause

        Long key = Long.valueOf(Thread.currentThread().getId());
        StringBuffer requestUrl = request.getRequestURL();
        if(request.getQueryString()!=null) {
            requestUrl.append("?");
            requestUrl.append(request.getQueryString());
        }
        MonitoredThread monitoredThread = new MonitoredThread(Thread.currentThread(),
            requestUrl.toString());
        activeThreads.put(key, monitoredThread);
        log.debug( "event/activeThread.put/key=" + key);

        try {
            getNext().event(request, response, event);
        } finally {
            activeThreads.remove(key);
            log.debug( "event/activeThread.remove/key=" + key);
            
            if (monitoredThread.markAsDone() == MonitoredThreadState.STUCK || 
            	monitoredThread.markAsDone() == MonitoredThreadState.HOGGING ) {
            	log.debug("event/add completed queue");
                completedStuckThreadsQueue.add(
                        new CompletedStuckThread(monitoredThread.getThread().getName(),
                            monitoredThread.getActiveTimeInMillis()));
            }
        }
    }

    @Override
    public void backgroundProcess() {
        super.backgroundProcess();

        long thresholdInMillis = stuckThreshold * 1000;
        long hoggingThresholdInMillis = hoggingThreshold * 1000;

        log.debug("backgroundProcess/start");
        // Check monitored threads, being careful that the request might have
        // completed by the time we examine it
        for (MonitoredThread monitoredThread : activeThreads.values()) {
            long activeTime = monitoredThread.getActiveTimeInMillis();

            log.debug("backgroundProcess/monitoredThread=" + monitoredThread.getThread().getId());
            // check is hogging thread
            if (activeTime >= hoggingThresholdInMillis && monitoredThread.markAsHoggingIfStillRunning()) {
            	hoggingCount.incrementAndGet();
            }
            
            if (activeTime >= thresholdInMillis && monitoredThread.markAsStuckIfStillRunning()) {
                int numStuckThreads = stuckCount.incrementAndGet();
                notifyStuckThreadDetected(monitoredThread, activeTime, numStuckThreads);
            }
        }
        
        // Check if any threads previously reported as stuck, have finished.
        for (CompletedStuckThread completedStuckThread = completedStuckThreadsQueue.poll();
            completedStuckThread != null; completedStuckThread = completedStuckThreadsQueue.poll()) {

            log.debug("backgroundProcess/monitoredThread=" + completedStuckThread.getName());
        	log.debug("Complete !!!");
        	hoggingCount.decrementAndGet();
            int numStuckThreads = stuckCount.decrementAndGet();            
            notifyStuckThreadCompleted(completedStuckThread.getName(),
                    completedStuckThread.getTotalActiveTime(), numStuckThreads);
        }
        log.debug("backgroundProcess/end");
    }

    public long[] getStuckThreadIds() {
        List<Long> idList = new ArrayList<Long>();
        for (MonitoredThread monitoredThread : activeThreads.values()) {
            if (monitoredThread.isMarkedAsStuck()) {
                idList.add(Long.valueOf(monitoredThread.getThread().getId()));
            }
        }

        long[] result = new long[idList.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = idList.get(i).longValue();
        }
        return result;
    }

    public int getStuckThreadCount() {
    	return stuckCount.get();
    }

    public int getActiveThreadCount() {
    	return activeThreads.size();
    }
    
    public int getHoggingThreadCount() {
    	return hoggingCount.get();
    }
    
    private static class MonitoredThread {

        /**
         * Reference to the thread to get a stack trace from background task
         */
        private final Thread thread;
        private final String requestUri;
        private final long start;
        private final AtomicInteger state = new AtomicInteger(
            MonitoredThreadState.RUNNING.ordinal());

        public MonitoredThread(Thread thread, String requestUri) {
            this.thread = thread;
            this.requestUri = requestUri;
            this.start = System.currentTimeMillis();
        }

        public Thread getThread() {
            return this.thread;
        }

        public String getRequestUri() {
            return requestUri;
        }

        public long getActiveTimeInMillis() {
            return System.currentTimeMillis() - start;
        }

        public Date getStartTime() {
            return new Date(start);
        }

        public boolean markAsHoggingIfStillRunning() {
        	log.debug("markAsHoggingIfStillRunning = " + this.state.get());
        	
            return this.state.compareAndSet(MonitoredThreadState.RUNNING.ordinal(),
                MonitoredThreadState.HOGGING.ordinal());
        }        
        
        public boolean markAsStuckIfStillRunning() {
        	log.debug("markAsStuckIfStillRunning = " + this.state.get());
//            return this.state.compareAndSet(MonitoredThreadState.RUNNING.ordinal(),
            return this.state.compareAndSet(MonitoredThreadState.HOGGING.ordinal(),
                MonitoredThreadState.STUCK.ordinal());
        }

        public MonitoredThreadState markAsDone() {
        	log.debug("markAsDone = " + this.state.get());
            int val = this.state.getAndSet(MonitoredThreadState.DONE.ordinal());
            return MonitoredThreadState.values()[val];
        }

        boolean isMarkedAsHogging() {
            return this.state.get() == MonitoredThreadState.HOGGING.ordinal();
        }
        
        boolean isMarkedAsStuck() {
            return this.state.get() == MonitoredThreadState.STUCK.ordinal();
        }
    }

    private static class CompletedStuckThread {

        private final String threadName;
        private final long totalActiveTime;

        public CompletedStuckThread(String threadName, long totalActiveTime) {
            this.threadName = threadName;
            this.totalActiveTime = totalActiveTime;
        }

        public String getName() {
            return this.threadName;
        }

        public long getTotalActiveTime() {
            return this.totalActiveTime;
        }
    }

    private enum MonitoredThreadState {
        RUNNING, HOGGING, STUCK, DONE;
    }

	private void registerStuckMonitor() {
    	log.info(">> Register MBean... : registerStuckMonitor");    			
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		try {
			if (mbs.isRegistered(ObjectNameUtil.getJMXObjectName()) )
				return;
			
			stuckMonitor = new StuckMonitorMBeanImpl(this);
			mbs.registerMBean(
					stuckMonitor,
					ObjectNameUtil.getJMXObjectName());

		} catch (Exception e) {
			log.warn("Unable to register StuckMonitorMBean. Statistics gathering will be disabled ", e);
		}
	}

	public void destroy() {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    	log.info("Unregister MBean... : StuckMonitor");    			

		try {
			mbs.unregisterMBean(ObjectNameUtil.getJMXObjectName());
		} catch (MBeanRegistrationException e) {
			e.printStackTrace();
		} catch (InstanceNotFoundException e) {
			e.printStackTrace();
		}		
		stuckMonitor = null;
	}

	
    public void start() throws LifecycleException {
    	log.info("StuckThreadDetectionValve Start");
        if ( stuckMonitor == null ) {
        	registerStuckMonitor();
        }    	
        support.fireLifecycleEvent(START_EVENT, this);
    }

    public void stop() throws LifecycleException {
    	log.info("StuckThreadDetectionValve Stop");
        support.fireLifecycleEvent(STOP_EVENT, this);
		this.destroy();
    }

    public void addLifecycleListener(LifecycleListener listener) {
        support.addLifecycleListener(listener);
    }

    public void removeLifecycleListener(LifecycleListener listener) {
        support.removeLifecycleListener(listener);
    }

    public LifecycleListener[] findLifecycleListeners() {
        return support.findLifecycleListeners();
    }	
}
