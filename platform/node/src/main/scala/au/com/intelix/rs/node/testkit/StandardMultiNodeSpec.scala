package au.com.intelix.rs.node.testkit

import au.com.intelix.rs.core.testkit.IsolatedActorSystems
import org.scalatest.FlatSpec

/**
  * Created by maks on 25/07/2016.
  */
trait StandardMultiNodeSpec extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems
