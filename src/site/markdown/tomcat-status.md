# Tomcat status

_Note: this page is only relevant for Spring Boot applications._

Additionally, when using the Tomcat starter, the Catalina mbeans will be used to show connector usage on the service status.

The connector status corresponds to the current usage of the thread pool, in the form of `busy threads/total available threads`.
So, `http-nio-8080: 5/10` means that 5 out of the 10 available threads of the `http-nio-8080` connector are actively serving requests.

This information is shown when requesting the service status:

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: active (running) since Wed 2018-03-07 23:01:36 WET; 11min ago
 Main PID: 21034 (java)
   Status: "Heap: 339 MiB/512 MiB, Non-heap: 118.1 MiB/121.5 MiB, Classes: 15734, http-nio-8080: 2/10"
   CGroup: /system.slice/myservice.service
           └─21034 /opt/jdk1.8.0/bin/java -XX:+ExitOnOutOfMemoryError -Xms256M -Xmx512M -XX:+UseG1GC -jar /opt/myservice/myservice.jar
```
