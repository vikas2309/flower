package dev.flower.android

import flwr.proto.FleetGrpc
import flwr.proto.FleetOuterClass.CreateNodeRequest
import flwr.proto.FleetOuterClass.CreateNodeResponse
import flwr.proto.FleetOuterClass.DeleteNodeRequest
import flwr.proto.FleetOuterClass.DeleteNodeResponse
import flwr.proto.FleetOuterClass.PullTaskInsRequest
import flwr.proto.FleetOuterClass.PullTaskInsResponse
import flwr.proto.FleetOuterClass.PushTaskResRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import flwr.proto.FlowerServiceGrpc
import flwr.proto.NodeOuterClass.Node
import flwr.proto.TaskOuterClass.TaskIns
import flwr.proto.TaskOuterClass.TaskRes
import flwr.proto.Transport.ServerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

internal class FlowerGRPC
@Throws constructor(
    channel: ManagedChannel,
    private val client: Client,
) {
    private val finishLatch = CountDownLatch(1)

    private val asyncStub = FlowerServiceGrpc.newStub(channel)!!

    private val requestObserver = asyncStub.join(object : StreamObserver<ServerMessage> {
        override fun onNext(msg: ServerMessage) {
            try {
                sendResponse(msg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onError(t: Throwable) {
            t.printStackTrace()
            finishLatch.countDown()
        }

        override fun onCompleted() {
            finishLatch.countDown()
        }
    })!!

    fun sendResponse(msg: ServerMessage) {
        val response = handleLegacyMessage(client, msg)
        requestObserver.onNext(response.first)
    }
}

suspend fun createFlowerService(
    serverAddress: String,
    useTLS: Boolean,
    client: Client,
) {
    FlowerGRPC(createChannel(serverAddress, useTLS), client)
}

/**
 * @param address Address of the gRPC server, like "dns:///$host:$port".
 */
suspend fun createChannel(address: String, useTLS: Boolean = false): ManagedChannel {
    val channelBuilder =
        ManagedChannelBuilder.forTarget(address).maxInboundMessageSize(HUNDRED_MEBIBYTE)
    if (!useTLS) {
        channelBuilder.usePlaintext()
    }
    return withContext(Dispatchers.IO) {
        channelBuilder.build()
    }
}

const val HUNDRED_MEBIBYTE = 100 * 1024 * 1024

internal class FlwrReRe
@Throws constructor(
    channel: ManagedChannel,
    private val client: Client,
) {

    private val KEYNODE = "node"
    private val KEYTASKINS = "currentTaskIns"

    private val finishLatch = CountDownLatch(1)

    private val asyncStub = FleetGrpc.newStub(channel)

    private val state = mutableMapOf<String, TaskIns?>()
    private val nodeStore = mutableMapOf<String, Node?>()

    fun createNode() {
        val createNodeRequest = CreateNodeRequest.newBuilder().build()

        asyncStub.createNode(createNodeRequest, object : StreamObserver<CreateNodeResponse> {
            override fun onNext(value: CreateNodeResponse?) {
                nodeStore[KEYNODE] = value?.node
            }

            override fun onError(t: Throwable?) {
                t?.printStackTrace()
                finishLatch.countDown()
            }

            override fun onCompleted() {
                finishLatch.countDown()
            }
        })
    }

    fun deleteNode() {
        nodeStore[KEYNODE]?.let { node ->
            val deleteNodeRequest = DeleteNodeRequest.newBuilder().setNode(node).build()
            asyncStub.deleteNode(deleteNodeRequest, object : StreamObserver<DeleteNodeResponse> {
                override fun onNext(value: DeleteNodeResponse?) {
                    nodeStore[KEYNODE] = null
                }

                override fun onError(t: Throwable?) {
                    t?.printStackTrace()
                    finishLatch.countDown()
                }

                override fun onCompleted() {
                    finishLatch.countDown()
                }
            })
        }
    }

    suspend fun receive(): Flow<TaskIns> = flow {
        val node = nodeStore[KEYNODE]
        if (node == null) {
            println("Node not available")
            return@flow
        }

        val request = PullTaskInsRequest.newBuilder().setNode(node).build()
        asyncStub.pullTaskIns(request, object : StreamObserver<PullTaskInsResponse> {
            override fun onNext(value: PullTaskInsResponse?) {
                val taskIns = value?.let { getTaskIns(it) }
                if (taskIns != null && validateTaskIns(taskIns, true)) {
                    state[KEYTASKINS] = taskIns
                    // Using emit inside the coroutine scope
                    kotlinx.coroutines.GlobalScope.launch {
                        emit(taskIns)
                    }
                }
            }

            override fun onError(t: Throwable?) {
                t?.printStackTrace()
                finishLatch.countDown()
            }

            override fun onCompleted() {
                finishLatch.countDown()
            }
        })
    }

    fun send(taskRes: TaskRes) {
        nodeStore[KEYNODE]?.let { node ->
            state[KEYTASKINS]?.let { taskIns ->
                if (validateTaskRes(taskRes)) {
                    taskRes
                        .toBuilder()
                        .setTaskId("")
                        .setGroupId(taskIns.groupId)
                        .setWorkloadId(taskIns.workloadId)
                        .task.toBuilder()
                        .setProducer(node)
                        .setConsumer(taskIns.task.producer)
                        .addAncestry(taskIns.taskId)
                    val request = PushTaskResRequest.newBuilder().addTaskResList(taskRes).build()
                    asyncStub.pushTaskRes(request, null)
                }
                state[KEYTASKINS] = null
            }
        }
    }
}
