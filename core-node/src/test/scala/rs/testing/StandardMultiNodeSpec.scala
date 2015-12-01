package rs.testing

import org.scalatest.FlatSpec

trait StandardMultiNodeSpec extends FlatSpec with ManagedNodeTestContext with IsolatedActorSystems


