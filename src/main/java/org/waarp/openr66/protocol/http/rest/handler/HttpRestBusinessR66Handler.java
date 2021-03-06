/**
   This file is part of Waarp Project.

   Copyright 2009, Frederic Bregier, and individual contributors by the @author
   tags. See the COPYRIGHT.txt in the distribution for a full listing of
   individual contributors.

   All Waarp Project is free software: you can redistribute it and/or 
   modify it under the terms of the GNU General Public License as published 
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   Waarp is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with Waarp .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.protocol.http.rest.handler;

import static org.waarp.openr66.context.R66FiniteDualStates.ERROR;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.waarp.common.database.data.AbstractDbData;
import org.waarp.common.json.JsonHandler;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.gateway.kernel.exception.HttpIncorrectRequestException;
import org.waarp.gateway.kernel.exception.HttpInvalidAuthenticationException;
import org.waarp.gateway.kernel.rest.HttpRestHandler;
import org.waarp.gateway.kernel.rest.RestConfiguration;
import org.waarp.gateway.kernel.rest.DataModelRestMethodHandler.COMMAND_TYPE;
import org.waarp.gateway.kernel.rest.HttpRestHandler.METHOD;
import org.waarp.gateway.kernel.rest.RestArgument;
import org.waarp.openr66.context.ErrorCode;
import org.waarp.openr66.context.R66Result;
import org.waarp.openr66.context.R66Session;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNotAuthenticatedException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolPacketException;
import org.waarp.openr66.protocol.http.rest.HttpRestR66Handler;
import org.waarp.openr66.protocol.http.rest.HttpRestR66Handler.RESTHANDLERS;
import org.waarp.openr66.protocol.localhandler.ServerActions;
import org.waarp.openr66.protocol.localhandler.packet.json.BusinessRequestJsonPacket;
import org.waarp.openr66.protocol.localhandler.packet.json.JsonPacket;
import org.waarp.openr66.protocol.utils.R66Future;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Business Http REST interface: http://host/business?... + BusinessRequestJsonPacket as GET
 * 
 * @author "Frederic Bregier"
 *
 */
public class HttpRestBusinessR66Handler extends HttpRestAbstractR66Handler {

    public static final String BASEURI = "business";
    /**
     * Internal Logger
     */
    private static final WaarpLogger logger = WaarpLoggerFactory
            .getLogger(HttpRestBusinessR66Handler.class);

    public HttpRestBusinessR66Handler(RestConfiguration config, METHOD... methods) {
        super(BASEURI, config, METHOD.OPTIONS);
        setIntersectionMethods(methods, METHOD.GET);
    }

    @Override
    public void endParsingRequest(HttpRestHandler handler, RestArgument arguments, RestArgument result, Object body)
            throws HttpIncorrectRequestException, HttpInvalidAuthenticationException {
        logger.debug("debug: {} ### {}", arguments, result);
        if (body != null) {
            logger.debug("Obj: {}", body);
        }
        handler.setWillClose(false);
        ServerActions serverHandler = ((HttpRestR66Handler) handler).getServerHandler();
        R66Session session = serverHandler.getSession();
        // now action according to body
        JsonPacket json = (JsonPacket) body;
        if (json == null) {
            result.setDetail("not enough information");
            setError(handler, result, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        result.getAnswer().put(AbstractDbData.JSON_MODEL, RESTHANDLERS.Business.name());
        try {
            if (json instanceof BusinessRequestJsonPacket) {//
                result.setCommand(ACTIONS_TYPE.ExecuteBusiness.name());
                BusinessRequestJsonPacket node = (BusinessRequestJsonPacket) json;
                R66Future future = serverHandler.businessRequest(node.isToApplied(), node.getClassName(),
                        node.getArguments(), node.getExtraArguments(), node.getDelay());
                if (future != null && !future.isSuccess()) {
                    R66Result r66result = future.getResult();
                    if (r66result == null) {
                        r66result = new R66Result(session, false, ErrorCode.ExternalOp, null);
                    }
                    logger.info("Task in Error:" + node.getClassName() + " " + r66result);
                    if (!r66result.isAnswered()) {
                        node.setValidated(false);
                        session.newState(ERROR);
                    }
                    result.setDetail("Task in Error:" + node.getClassName() + " " + r66result);
                    setError(handler, result, HttpResponseStatus.NOT_ACCEPTABLE);
                } else {
                    R66Result r66result = future.getResult();
                    if (r66result != null && r66result.getOther() != null) {
                        result.setDetail(r66result.getOther().toString());
                        node.setArguments(r66result.getOther().toString());
                    }
                    setOk(handler, result, json, HttpResponseStatus.OK);
                }
            } else {
                logger.info("Validation is ignored: " + json);
                result.setDetail("Unknown command");
                setError(handler, result, json, HttpResponseStatus.PRECONDITION_FAILED);
            }
        } catch (OpenR66ProtocolNotAuthenticatedException e) {
            throw new HttpInvalidAuthenticationException(e);
        } catch (OpenR66ProtocolPacketException e) {
            throw new HttpIncorrectRequestException(e);
        }
    }

    protected ArrayNode getDetailedAllow() {
        ArrayNode node = JsonHandler.createArrayNode();

        if (this.methods.contains(METHOD.GET)) {
            BusinessRequestJsonPacket node3 = new BusinessRequestJsonPacket();
            node3.setRequestUserPacket();
            node3.setComment("Business execution request (GET)");
            node3.setClassName("Class name to execute");
            node3.setArguments("Arguments of the execution");
            node3.setExtraArguments("Extra arguments");
            ObjectNode node2;
            ArrayNode node1 = JsonHandler.createArrayNode();
            try {
                node1.add(node3.createObjectNode());
                node2 = RestArgument.fillDetailedAllow(METHOD.GET, this.path, ACTIONS_TYPE.ExecuteBusiness.name(),
                        node3.createObjectNode(), node1);
                node.add(node2);
            } catch (OpenR66ProtocolPacketException e1) {
            }
        }
        ObjectNode node2 = RestArgument.fillDetailedAllow(METHOD.OPTIONS, this.path, COMMAND_TYPE.OPTIONS.name(), null,
                null);
        node.add(node2);

        return node;
    }
}
