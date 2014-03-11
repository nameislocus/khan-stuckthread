StuckThread Monitoring for JBossWeb(JBoss EAP 6/AS 7)
=====================================================
Stuck Thread Monitoring for JBoss EAP 6(AS 7)

## Stuck Thread란?
WebLogic에서는 StuckThread를 모니터링 할 수 있습니다.
기본값은 600초로 600초 동안 실행되고 있는 스레드가 있으면, 이것을 Stuck Thread라고 합니다.
문서에는 정확하게 몇 초라고 지정되어 있진 않지만, Stuck Thread가 되기 전 단계인 Hogging Thread라는 단계도 하나 더 있습니다.

스레드의 실행 상태 단계를 보면 아래와 같습니다.
```
 Standby ==> Active ==> Hogging ==> Stuck
         <==--------------------------+               
```
## Stuck Thread 모니터링
JBoss에서는 이런 Stuck Thread를 모니터링하는 방법이 없습니다.
그래서, JBoss에서도 WebLogic 처럼 Stuck Thread를 모니터링하는 Valve를 만들었습니다.
Valve는 Tomcat의 Request에 대한 파이프라인 프로세싱을 위한 컴포넌트입니다.
 
JBoss EAP 6.1.1(AS 7.2)부터 Global Valve를 지원합니다.
JBoss EAP 6 초기 버전에는 Web App별 Valve만 지원했기 때문에 커뮤니티에서 Global Valve에 대한 요청이 많았고, EAP 6.1.1버전부터 다시 추가되었습니다.

WebLogic에서는 Hogging 스레드는 정확히 몇 초이상 실행되는 스레드를 Hogging이라고 명시되어 있지 않아서, 몇 초이상 실행되는 스레드를 Hogging단계라고 설정할 수 있도록 하였습니다.

## Stuck Thread 모니터링 설정 방법
JBoss의 웹 서브시스템에 아래와 같이 StuckThread Monitoring Valve를 설정하면 됩니다.

```xml
<valve name="stuckthreadValve" module="com.opennaru.khan.stuckthread" class-name="com.opennaru.khan.stuckthread.StuckThreadDetectionValve">
    <param param-name="stuckThreshold" param-value="600"/>
    <param param-name="hoggingThreshold" param-value="60"/>
</valve>
```

빌드한 jar 파일은 com/opennaru/khan/stuckthread/main 디렉터리에 복사한 후 모듈로 등록하여야 합니다.

## Stuck Thread 모니터링 MBean
StuckThread에 대한 모니터링 정보를 추가된 MBean을 통해서 확인할 수 있습니다.
<div align="center">
  <p><img src="https://raw.github.com/nameislocus/khan-stuckthread/master/resources/config/stuckthread-mbean.png"></p>
</div>

StuckThread가 발생하면 stdout에 호출한 URL 정보와 Stuck Thread가 발생한 애플리케이션을 확인할 수 있도록, StackTrace가 다음과 같이 표시됩니다.

## Stuck Thread STDOUT 출력
```
10:44:50,192 WARN  [com.opennaru.khan.stuckthread.StuckThreadDetectionValve] (ContainerBackgroundProcessor[StandardEngine[jboss.web]]) stuckThreadDetectionValve.notifyStuckThreadDetected
ThreadName=http-localhost/127.0.0.1:8080-1
activeTime=15655
startTime=Mon Mar 10 10:44:34 KST 2014
numStuckThreads=1
requestURI=http://127.0.0.1:8080/sample1/stuck.jsp
stuckThreshold=10
: java.lang.Throwable
	at java.lang.Thread.sleep(Native Method) [rt.jar:1.6.0_45]
	at org.apache.jsp.stuck_jsp._jspService(stuck_jsp.java:81)
	at org.apache.jasper.runtime.HttpJspBase.service(HttpJspBase.java:69) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at javax.servlet.http.HttpServlet.service(HttpServlet.java:847) [jboss-servlet-api_3.0_spec-1.0.2.Final-redhat-1.jar:1.0.2.Final-redhat-1]
	at org.apache.jasper.servlet.JspServletWrapper.service(JspServletWrapper.java:365) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.jasper.servlet.JspServlet.serviceJspFile(JspServlet.java:309) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.jasper.servlet.JspServlet.service(JspServlet.java:242) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at javax.servlet.http.HttpServlet.service(HttpServlet.java:847) [jboss-servlet-api_3.0_spec-1.0.2.Final-redhat-1.jar:1.0.2.Final-redhat-1]
	at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:295) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:214) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at com.opennaru.khan.session.filter.KhanSessionFilter.doFilter(KhanSessionFilter.java:261)
	at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:246) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:214) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:230) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:149) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:407) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.jboss.as.web.security.SecurityContextAssociationValve.invoke(SecurityContextAssociationValve.java:169) [jboss-as-web-7.3.0.Final-redhat-14.jar:7.3.0.Final-redhat-14]
	at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:145) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:97) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-redhat-1]
	at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:102) [jbossweb-7.2.2.Final-redhat-1.jar:7.2.2.Final-[0m10:46:20,205 INFO  [stdout] (ContainerBackgroundProcessor[StandardEngine[jboss.web]]) KhanSessionManager/instance=com.opennaru.khan.session.manager.KhanSessionManager@65067a13
```


