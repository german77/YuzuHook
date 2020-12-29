package yuzu.emu.org

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.*
import java.util.zip.CRC32


class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var listenSocket:DatagramSocket
    lateinit var listenPacket : DatagramPacket  //Waiting to listen to the package
    private lateinit var sendPacket : DatagramPacket    //Package sent to destination
    val RecvBuf = ByteArray(1024)
    val ReplyBuf = ByteArray(1024)
    val MaxProtocolVersion = 1001;
    var packetCounter=0L
    var clients =  mutableListOf<ClientEndpoint>()
    var gyro: Motion= Motion(0.0f,0.0f,0.0f)
    var accel: Motion = Motion(0.0f,0.0f,0.0f)
    var touch: Touch = Touch(0,0.0f,0.0f,0.0f)
    enum class MessageType(val value: Long) {
        DSUC_VersionReq(0x100000L),
        DSUS_VersionRsp(0x100000L),
        DSUC_ListPorts(0x100001L),
        DSUS_PortInfo(0x100001L),
        DSUC_PadDataReq(0x100002L),
        DSUS_PadDataRsp(0x100002L)
    }
    data class ClientEndpoint(val ip: InetAddress, val port: Int, var timeout:Int)
    data class Motion(var x: Float, var y: Float, var z:Float)
    data class Touch(var id: Int,var x: Float, var y: Float, var p:Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also { gyroscope ->
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also { gyroscope ->
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        }

        val connectButton = findViewById<Button>(R.id.btnConnect)
        val serverTextView = findViewById<TextView>(R.id.txtServer)

        connectButton.setOnClickListener{
            val rand = Random().nextInt(10);
            serverTextView.text = rand.toString()
        }
        //Keep a socket open to listen to all the UDP trafic that is destined for this port
        listenSocket = DatagramSocket(26760)
        listenSocket.broadcast = true

        Thread(Runnable {
            while (true){
                receiveUDP()
            }
        }).start()

        Thread(Runnable {
            while (true){
                sendControlerData()
                Thread.sleep(250)
            }
        }).start()

    }
    fun intToByteArray(value: Int): ByteArray {
        var newvalue = value
        val b =  ByteArray(2)
        for (i in 0..1) {
            b[i]=newvalue.toByte()
            newvalue=newvalue shr 8
        }
        return b;
    }
    fun longToByteArray(value: Long): ByteArray {
        //println(value)
        var newvalue = value
        val b =  ByteArray(4)
        for (i in 0..3) {
            b[i]=newvalue.toByte()
            newvalue=newvalue shr 8
            //println("b$i ${b[i]}")
        }
        return b;
    }

    fun byteArrayToInt(buffer: ByteArray, offset: Int): Int{
        var value=0
        for (i in 0..1) {
            if(i+offset >= buffer.size){
                return value
            }
            value *= 256
            value += buffer[offset+1-i].toUByte().toInt()
        }
        return value
    }
    fun byteArrayToLong(buffer: ByteArray, offset: Int): Long{
        var value: Long=0
        for (i in 0..3) {
            if(i+offset >= buffer.size){
                return value
            }
            value *= 256
            value += buffer[offset+3-i].toUByte().toInt()
        }
        return value
    }

    fun BeginPacket(packetBuffer:ByteArray, version: Int):Int{
        if(packetBuffer.size<16)
            return 0
        var currIdx = 0
        packetBuffer[currIdx++] = 'D'.toByte()
        packetBuffer[currIdx++] = 'S'.toByte()
        packetBuffer[currIdx++] = 'U'.toByte()
        packetBuffer[currIdx++] = 'S'.toByte()
        intToByteArray(version).copyInto(packetBuffer,currIdx)
        currIdx += 2
        intToByteArray(packetBuffer.size-16).copyInto(packetBuffer,currIdx)
        currIdx += 2

        currIdx += 4 //crc

        //clientid
        intToByteArray(123).copyInto(packetBuffer,currIdx)
        currIdx += 4
        return currIdx
    }
    fun getCRC(packetBuffer: ByteArray): Long {
        val crc = CRC32()
        crc.update(packetBuffer)
        return crc.getValue()
    }
    fun FinishPacket(packetBuffer: ByteArray) {
        val crc = getCRC(packetBuffer)
        longToByteArray(crc).copyInto(packetBuffer,8)
   }

    fun SendPacket(packet:ByteArray, address: InetAddress, port: Int){
        try {
            val sendData =  ByteArray(16+packet.size)
            var currIdx = BeginPacket(sendData,1001);
            packet.copyInto(sendData,currIdx)
            FinishPacket(sendData)
            //println("Sending message size:${sendData.size}, device $address:$port")
            val sendPacket = DatagramPacket(sendData, sendData.size, address, port)
            listenSocket.send(sendPacket)

        }catch (e: Exception) {}
    }

    fun IsPacketValid(packet:ByteArray):Boolean{
        if(packet.size < 16){
            println("Invalid header size ${packet.size}");
            return false;
        }
        if (packet[0] != 'D'.toByte() || packet[1] != 'S'.toByte() ||
                packet[2] != 'U'.toByte() || packet[3] != 'C'.toByte()){
            println("Invalid magic value");
            return false
        }
        val protocol_version=byteArrayToInt(packet,4)
        if(protocol_version != 1001){
            println("Invalid protocol version $protocol_version");
            return false;
        }
        val payload_length=byteArrayToInt(packet,6)
        if(payload_length +16 >packet.size){
            println("Invalid package size $payload_length");
            return false;
        }

        val crc= byteArrayToLong(packet,8)
        val crc_buffer = ByteArray(16+payload_length)
        packet.copyInto(crc_buffer,0,0,crc_buffer.size)
        longToByteArray(0).copyInto(crc_buffer,8)
        if(crc != getCRC(crc_buffer)){
            println("Invalid crc check $crc != ${getCRC(crc_buffer)}");
            return false
        }
        return true;
    }

    fun getControllerStatus():ByteArray{
        val packet = ByteArray(16)
        val PadId = 0;
        val battery = -1;
        val model = 2;
        val constate = 2;
        val connection = 3;

        var outIdx = 0
        longToByteArray(MessageType.DSUS_PortInfo.value).copyInto(packet,0)
        outIdx += 4

        packet[outIdx++] = PadId.toByte()
        packet[outIdx++] = constate.toByte()
        packet[outIdx++]= model.toByte()
        packet[outIdx++] = connection.toByte()
        //mac
        packet[outIdx++] = 1
        packet[outIdx++] = 2
        packet[outIdx++] = 3
        packet[outIdx++] = 4
        packet[outIdx++] = 5
        packet[outIdx++] = 6

        packet[outIdx++] = battery.toByte()
        packet[outIdx++] = 0
        return packet
    }

    fun updateClient(address: InetAddress, port: Int){
        var notfound = true;
        for(client in clients){
            if(client.ip==address && client.port==port){
                notfound=false;
                client.timeout=0;
                break
            }
        }
        if(notfound){
            var client:ClientEndpoint= ClientEndpoint(address,port,0)
            clients.add(client)
        }
    }
    fun sendControlerData(){
        val PadId = 0;
        val battery = -1;
        val model = 2;
        val constate = 2;
        val connection = 3;

        for(client in clients) {
            if(client.timeout++ > 300){
                //clients.remove(client)
                continue;
            }

            val outputData = ByteArray(84)
            var outIdx = 0
            longToByteArray(MessageType.DSUS_PadDataRsp.value).copyInto(outputData,outIdx)
            outIdx += 4

            outputData[outIdx++] = PadId.toByte()
            outputData[outIdx++] = constate.toByte()
            outputData[outIdx++] = model.toByte()
            outputData[outIdx++] = connection.toByte()
            //mac
            outputData[outIdx++] = 1
            outputData[outIdx++] = 2
            outputData[outIdx++] = 3
            outputData[outIdx++] = 4
            outputData[outIdx++] = 5
            outputData[outIdx++] = 6


            outputData[outIdx++] = battery.toByte()
            outputData[outIdx++] = 1


            longToByteArray(packetCounter).copyInto(outputData,outIdx)
            outIdx += 4;

            SendPacket(outputData,client.ip,client.port)


        }
        packetCounter++
    }
    fun ReportToBuffer(outputData:ByteArray,outIdx_:Int):Boolean{
        var outIdx = outIdx_
        outputData[outIdx] = 0 //button data
        outputData[++outIdx] = 0 //button data 2


        outputData[++outIdx] = 0
        outputData[++outIdx] = 0 // no touch pad

        //left stick
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0

        //right stick
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0


        outputData[++outIdx] = 0
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0

        outputData[++outIdx] = 0
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0

        outputData[++outIdx] = 0
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0
        outputData[++outIdx] = 0

        outIdx++

        //DS4 only: touchpad points
        for (i in 0..1) {
            outIdx += 6
        }

        //motion timestamp
        outIdx += 8;

        //accelerometer
        longToByteArray(accel.x.toLong()).copyInto(outputData,outIdx)
        outIdx += 4;
        longToByteArray(accel.y.toLong()).copyInto(outputData,outIdx)
        outIdx += 4;
        longToByteArray(accel.z.toLong()).copyInto(outputData,outIdx)
        //gyroscope
        longToByteArray(gyro.x.toLong()).copyInto(outputData,outIdx)
        outIdx += 4;
        longToByteArray(gyro.y.toLong()).copyInto(outputData,outIdx)
        outIdx += 4;
        longToByteArray(gyro.z.toLong()).copyInto(outputData,outIdx)
        return true
    }

    open fun receiveUDP() {
        val buffer = ByteArray(1024)
        try {
            val packet = DatagramPacket(buffer, buffer.size)
            listenSocket.receive(packet)
            if(!IsPacketValid(packet.data)){
                println("invalid package")
                return;
            }
            println("valid package from ${packet.socketAddress}:${packet.address}:${packet.port}")
            val payload_length=byteArrayToInt(packet.data,6)
            val id=byteArrayToLong(packet.data,12)
            val type=byteArrayToLong(packet.data,16)
            updateClient(packet.address,packet.port)
            when (type){
                MessageType.DSUC_VersionReq.value ->{
                    val outputData = ByteArray(8)
                    var outIdx = 0
                    longToByteArray(MessageType.DSUS_VersionRsp.value).copyInto(outputData,outIdx)
                    outIdx += 4
                    intToByteArray(MaxProtocolVersion).copyInto(outputData,outIdx)
                    outIdx += 2
                    outputData[outIdx++] = 0
                    outputData[outIdx++] = 0

                    SendPacket(outputData,packet.address,packet.port)
                }
                MessageType.DSUC_ListPorts.value ->{
                    val flag=packet.data[20]
                    val id=packet.data[21]
                    val mac="${packet.data[22]}.${packet.data[23]}.${packet.data[24]}.${packet.data[25]}.${packet.data[26]}.${packet.data[27]}";
                    println("flag $flag, id $id, mac $mac")
                    val packetout = getControllerStatus()
                    SendPacket(packetout,packet.address,packet.port)

                }
                MessageType.DSUC_PadDataReq.value ->{
                    println("not supported DSUC_PadDataReq mode")
                }
            }


        } catch (e: Exception) {
            println("open fun receiveUDP catch exception.$e")
            e.printStackTrace()
        } finally {
            //listenSocket?.close()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    @SuppressLint("SetTextI18n")
    override fun onSensorChanged(event: SensorEvent?) {
        val GyroXTextView = findViewById<TextView>(R.id.txtGyroX)
        val GyroYTextView = findViewById<TextView>(R.id.txtGyroY)
        val GyroZTextView = findViewById<TextView>(R.id.txtGyroZ)
        val AccXTextView = findViewById<TextView>(R.id.txtAccX)
        val AccYTextView = findViewById<TextView>(R.id.txtAccY)
        val AccZTextView = findViewById<TextView>(R.id.txtAccZ)
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                gyro.x = event.values[0]
                gyro.y = event.values[1]
                gyro.z = event.values[2]
                AccXTextView.text = "X: " + gyro.x.toString()
                AccYTextView.text = "Y: " + gyro.y.toString()
                AccZTextView.text = "Z: " + gyro.z.toString()
            }
            Sensor.TYPE_GYROSCOPE -> {
                accel.x = event.values[0]/9.8f
                accel.y = event.values[1]/9.8f
                accel.z = event.values[2]/9.8f
                GyroXTextView.text = "X: " + accel.x.toString()
                GyroYTextView.text = "Y: " + accel.y.toString()
                GyroZTextView.text = "Z: " + accel.z.toString()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {

            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listView = findViewById<ListView>(R.id.TouchList)
        val listItems = arrayOfNulls<String>(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            val x = event.getX(i)
            val y = event.getY(i)
            val p = event.getPressure(i)
            listItems[i] = "X: $x Y: $y P: $p"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listItems)
        return true;
    }
}
