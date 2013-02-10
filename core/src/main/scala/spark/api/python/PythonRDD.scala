package spark.api.python

import java.io._
import java.net._
import java.util.{List => JList, ArrayList => JArrayList, Collections}

import scala.collection.JavaConversions._
import scala.io.Source

import spark.api.java.{JavaSparkContext, JavaPairRDD, JavaRDD}
import spark.broadcast.Broadcast
import spark._
import spark.rdd.PipedRDD


private[spark] class PythonRDD[T: ClassManifest](
    parent: RDD[T],
    command: Seq[String],
    envVars: java.util.Map[String, String],
    preservePartitoning: Boolean,
    pythonExec: String,
    broadcastVars: JList[Broadcast[Array[Byte]]],
    accumulator: Accumulator[JList[Array[Byte]]])
  extends RDD[Array[Byte]](parent) {

  // Similar to Runtime.exec(), if we are given a single string, split it into words
  // using a standard StringTokenizer (i.e. by spaces)
  def this(parent: RDD[T], command: String, envVars: java.util.Map[String, String],
      preservePartitoning: Boolean, pythonExec: String,
      broadcastVars: JList[Broadcast[Array[Byte]]],
      accumulator: Accumulator[JList[Array[Byte]]]) =
    this(parent, PipedRDD.tokenize(command), envVars, preservePartitoning, pythonExec,
      broadcastVars, accumulator)

  override def getSplits = parent.splits

  override val partitioner = if (preservePartitoning) parent.partitioner else None

  override def compute(split: Split, context: TaskContext): Iterator[Array[Byte]] = {
    val SPARK_HOME = new ProcessBuilder().environment().get("SPARK_HOME")

    val pb = new ProcessBuilder(Seq(pythonExec, SPARK_HOME + "/python/pyspark/worker.py"))
    // Add the environmental variables to the process.
    val currentEnvVars = pb.environment()

    for ((variable, value) <- envVars) {
      currentEnvVars.put(variable, value)
    }

    val proc = pb.start()
    val env = SparkEnv.get

    // Start a thread to print the process's stderr to ours
    new Thread("stderr reader for " + command) {
      override def run() {
        for (line <- Source.fromInputStream(proc.getErrorStream).getLines) {
          System.err.println(line)
        }
      }
    }.start()

    // Start a thread to feed the process input from our parent's iterator
    new Thread("stdin writer for " + command) {
      override def run() {
        SparkEnv.set(env)
        val out = new PrintWriter(proc.getOutputStream)
        val dOut = new DataOutputStream(proc.getOutputStream)
        // Split index
        dOut.writeInt(split.index)
        // sparkFilesDir
        PythonRDD.writeStringAsPickle(SparkFiles.getRootDirectory, dOut)
        // Broadcast variables
        dOut.writeInt(broadcastVars.length)
        for (broadcast <- broadcastVars) {
          dOut.writeLong(broadcast.id)
          dOut.writeInt(broadcast.value.length)
          dOut.write(broadcast.value)
          dOut.flush()
        }
        // Serialized user code
        for (elem <- command) {
          out.println(elem)
        }
        out.flush()
        // Data values
        for (elem <- parent.iterator(split, context)) {
          PythonRDD.writeAsPickle(elem, dOut)
        }
        dOut.flush()
        out.flush()
        proc.getOutputStream.close()
      }
    }.start()

    // Return an iterator that read lines from the process's stdout
    val stream = new DataInputStream(proc.getInputStream)
    return new Iterator[Array[Byte]] {
      def next(): Array[Byte] = {
        val obj = _nextObj
        _nextObj = read()
        obj
      }

      private def read(): Array[Byte] = {
        try {
          stream.readInt() match {
            case length if length > 0 =>
              val obj = new Array[Byte](length)
              stream.readFully(obj)
              obj
            case SpecialLengths.PYTHON_EXCEPTION_THROWN =>
              // Signals that an exception has been thrown in python
              val exLength = stream.readInt()
              val obj = new Array[Byte](exLength)
              stream.readFully(obj)
              throw new PythonException(new String(obj))
            case SpecialLengths.END_OF_DATA_SECTION =>
              // We've finished the data section of the output, but we can still read some
              // accumulator updates; let's do that, breaking when we get EOFException
              while (true) {
                val len2 = stream.readInt()
                val update = new Array[Byte](len2)
                stream.readFully(update)
                accumulator += Collections.singletonList(update)
              }
              new Array[Byte](0)
          }
        } catch {
          case eof: EOFException => {
            val exitStatus = proc.waitFor()
            if (exitStatus != 0) {
              throw new Exception("Subprocess exited with status " + exitStatus)
            }
            new Array[Byte](0)
          }
          case e => throw e
        }
      }

      var _nextObj = read()

      def hasNext = _nextObj.length != 0
    }
  }

  val asJavaRDD : JavaRDD[Array[Byte]] = JavaRDD.fromRDD(this)
}

/** Thrown for exceptions in user Python code. */
private class PythonException(msg: String) extends Exception(msg)

/**
 * Form an RDD[(Array[Byte], Array[Byte])] from key-value pairs returned from Python.
 * This is used by PySpark's shuffle operations.
 */
private class PairwiseRDD(prev: RDD[Array[Byte]]) extends
  RDD[(Array[Byte], Array[Byte])](prev) {
  override def getSplits = prev.splits
  override def compute(split: Split, context: TaskContext) =
    prev.iterator(split, context).grouped(2).map {
      case Seq(a, b) => (a, b)
      case x          => throw new Exception("PairwiseRDD: unexpected value: " + x)
    }
  val asJavaPairRDD : JavaPairRDD[Array[Byte], Array[Byte]] = JavaPairRDD.fromRDD(this)
}

private[spark] object SpecialLengths {
  val END_OF_DATA_SECTION = -1
  val PYTHON_EXCEPTION_THROWN = -2
}

private[spark] object PythonRDD {

  /**
   * Write strings, serialized Python objects, or pairs of serialized objects to a data output
   * stream.
   *
   * The data format is a 32-bit integer representing the object's length (in bytes), followed by
   * the serialized data.
   *
   * @param elem the object to write
   * @param dOut a data output stream
   */
  def writeAsPickle(elem: Any, dOut: DataOutputStream) {
    if (elem.isInstanceOf[Array[Byte]]) {
      val arr = elem.asInstanceOf[Array[Byte]]
      dOut.writeInt(arr.length)
      dOut.write(arr)
    } else if (elem.isInstanceOf[scala.Tuple2[Array[Byte], Array[Byte]]]) {
      val t = elem.asInstanceOf[scala.Tuple2[Array[Byte], Array[Byte]]]
      writeAsPickle(t._1, dOut)
      writeAsPickle(t._2, dOut)
    } else if (elem.isInstanceOf[String]) {
      val s = elem.asInstanceOf[String].getBytes("UTF-8")
      dOut.writeInt(s.length)
      dOut.write(s)
    } else {
      throw new Exception("Unexpected RDD type: " + elem.getClass)
    }
  }

  def writeStringAsPickle(elem: String, dOut: DataOutputStream) {
    val s = elem.getBytes("UTF-8")
    val length = 2 + 1 + 4 + s.length + 1
    dOut.writeInt(length)
    dOut.writeByte(Pickle.PROTO)
    dOut.writeByte(Pickle.TWO)
    dOut.write(Pickle.BINUNICODE)
    dOut.writeInt(Integer.reverseBytes(s.length))
    dOut.write(s)
    dOut.writeByte(Pickle.STOP)
  }

  def readRDDFromFile(sc: JavaSparkContext, filename: String, parallelism: Int) :
  JavaRDD[Array[Byte]] = {
    val file = new DataInputStream(new FileInputStream(filename))
    val objs = new collection.mutable.ArrayBuffer[Array[Byte]]
    try {
      while (true) {
        val length = file.readInt()
        val obj = new Array[Byte](length)
        file.readFully(obj)
        objs.append(obj)
      }
    } catch {
      case eof: EOFException => {}
      case e => throw e
    }
    JavaRDD.fromRDD(sc.sc.parallelize(objs, parallelism))
  }

  def writeToFile[T](items: java.util.Iterator[T], filename: String) {
    import scala.collection.JavaConverters._
    writeToFile(items.asScala, filename)
  }

  def writeToFile[T](items: Iterator[T], filename: String) {
    val file = new DataOutputStream(new FileOutputStream(filename))
    for (item <- items) {
      writeAsPickle(item, file)
    }
    file.close()
  }

  def takePartition[T](rdd: RDD[T], partition: Int): Iterator[T] = {
    implicit val cm : ClassManifest[T] = rdd.elementClassManifest
    rdd.context.runJob(rdd, ((x: Iterator[T]) => x.toArray), Seq(partition), true).head.iterator
  }
}

/**
 * Pickle module:
 *
 *    http://docs.python.org/2/library/pickle.html
 *
 * The pickle protocol is documented in the source of the `pickle` and `pickletools` modules:
 *
 *    http://hg.python.org/cpython/file/2.6/Lib/pickle.py
 *    http://hg.python.org/cpython/file/2.6/Lib/pickletools.py
*/
private object Pickle {
  val PROTO: Byte = 0x80.toByte
  val TWO: Byte = 0x02.toByte
  val BINUNICODE: Byte = 'X'
  val STOP: Byte = '.'
  val TUPLE2: Byte = 0x86.toByte
  val EMPTY_LIST: Byte = ']'
  val MARK: Byte = '('
  val APPENDS: Byte = 'e'
}

private class BytesToString extends spark.api.java.function.Function[Array[Byte], String] {
  override def call(arr: Array[Byte]) : String = new String(arr, "UTF-8")
}

/**
 * Internal class that acts as an `AccumulatorParam` for Python accumulators. Inside, it
 * collects a list of pickled strings that we pass to Python through a socket.
 */
class PythonAccumulatorParam(@transient serverHost: String, serverPort: Int)
  extends AccumulatorParam[JList[Array[Byte]]] {
  
  override def zero(value: JList[Array[Byte]]): JList[Array[Byte]] = new JArrayList

  override def addInPlace(val1: JList[Array[Byte]], val2: JList[Array[Byte]])
      : JList[Array[Byte]] = {
    if (serverHost == null) {
      // This happens on the worker node, where we just want to remember all the updates
      val1.addAll(val2)
      val1
    } else {
      // This happens on the master, where we pass the updates to Python through a socket
      val socket = new Socket(serverHost, serverPort)
      val in = socket.getInputStream
      val out = new DataOutputStream(socket.getOutputStream)
      out.writeInt(val2.size)
      for (array <- val2) {
        out.writeInt(array.length)
        out.write(array)
      }
      out.flush()
      // Wait for a byte from the Python side as an acknowledgement
      val byteRead = in.read()
      if (byteRead == -1) {
        throw new SparkException("EOF reached before Python server acknowledged")
      }
      socket.close()
      null
    }
  }
}
