package com.socrata.curator

/** Zookeeper Lookup Failed. */
case class ServiceDiscoveryException(message: String) extends Exception(message)
