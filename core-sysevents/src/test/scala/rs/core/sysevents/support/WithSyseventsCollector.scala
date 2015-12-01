package rs.core.sysevents.support

trait WithSyseventsCollector {
  System.setProperty("sysevents.publisher-provider", classOf[TestSyseventPublisher].getName)

}
