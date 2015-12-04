package org.visallo.core.ping;

import com.google.inject.Inject;
import org.json.JSONObject;
import org.vertexium.Authorizations;
import org.vertexium.Graph;
import org.vertexium.Vertex;
import org.visallo.core.model.Description;
import org.visallo.core.model.Name;
import org.visallo.core.model.longRunningProcess.LongRunningProcessWorker;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.util.ClientApiConverter;

@Name("Ping")
@Description("run on special Ping vertices to measure LRP wait time")
public class PingLongRunningProcess extends LongRunningProcessWorker {
    private final UserRepository userRepository;
    private final Graph graph;

    @Inject
    public PingLongRunningProcess(
            UserRepository userRepository,
            Graph graph,
            AuthorizationRepository authorizationRepository
    ) {
        this.userRepository = userRepository;
        this.graph = graph;

        PingUtil.setup(authorizationRepository, userRepository);
    }

    @Override
    protected void processInternal(JSONObject jsonObject) {
        PingLongRunningProcessQueueItem queueItem = ClientApiConverter.toClientApi(jsonObject.toString(), PingLongRunningProcessQueueItem.class);
        Authorizations authorizations = userRepository.getAuthorizations(userRepository.getSystemUser());
        Vertex vertex = graph.getVertex(queueItem.getVertexId(), authorizations);
        PingUtil.lrpUpdate(vertex, graph, authorizations);
    }

    @Override
    public boolean isHandled(JSONObject jsonObject) {
        return PingLongRunningProcessQueueItem.isHandled(jsonObject);
    }
}
