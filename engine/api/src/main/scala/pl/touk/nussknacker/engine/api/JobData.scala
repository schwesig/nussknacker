package pl.touk.nussknacker.engine.api

import argonaut._,Argonaut._,ArgonautShapeless._

case class JobData(metaData: MetaData, processVersion: ProcessVersion)
