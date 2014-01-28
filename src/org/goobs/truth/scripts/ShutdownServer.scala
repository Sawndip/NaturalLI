package org.goobs.truth.scripts

import java.net.Socket
import java.io.{DataOutputStream, DataInputStream}

import org.goobs.truth.Props
import org.goobs.truth.Messages._

/**
 * Assue a simple shutdown request to the inference server.
 *
 * @author gabor
 */
object ShutdownServer {


  def main(args:Array[String]):Unit = {
    // Create dummy query
    val query = Query.newBuilder().setQueryFact(Fact.newBuilder.addWord(Word.newBuilder().setWord(0).build()).build()).setUseRealWorld(false).setShutdownServer(true).build()

    // Set up connection
    val sock = new Socket(Props.SERVER_HOST, Props.SERVER_PORT)
    val toServer = new DataOutputStream(sock.getOutputStream)

    // Write query
    query.writeTo(toServer)
    sock.shutdownOutput()  // signal end of transmission
  }
}