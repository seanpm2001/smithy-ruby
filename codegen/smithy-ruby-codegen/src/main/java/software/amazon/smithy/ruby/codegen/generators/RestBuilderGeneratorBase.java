/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.ruby.codegen.generators;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpPayloadTrait;
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait;
import software.amazon.smithy.model.traits.HttpQueryParamsTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait;
import software.amazon.smithy.ruby.codegen.GenerationContext;
import software.amazon.smithy.ruby.codegen.Hearth;
import software.amazon.smithy.ruby.codegen.RubyImportContainer;
import software.amazon.smithy.ruby.codegen.util.TimestampFormat;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Base class for Builders for REST protocols which support HTTP binding traits.
 * <p>
 * Protocols should extend this class to get common functionality -
 * generates the framework and non-protocol specific parts of
 * builders.rb to handle http binding traits.
 */
@SmithyUnstableApi
public abstract class RestBuilderGeneratorBase extends BuilderGeneratorBase {

    private static final Logger LOGGER =
            Logger.getLogger(RestBuilderGeneratorBase.class.getName());

    public RestBuilderGeneratorBase(GenerationContext context) {
        super(context);
    }

    /**
     * Called to render an operation's body builder when it has a Payload member.
     * The generated code should serialize the payloadMember to the requests body.
     * The class and build method skeleton is rendered outside of this method
     * and implementations only need to write the build method body.
     *
     * <p>The following example shows the generated skeleton and an example of what
     * this method is expected to render.</p>
     * <pre>{@code
     * class HttpPayloadTraits
     *   def self.build(http_req, input:)
     *     http_req.http_method = 'POST'
     *     http_req.append_path('/HttpPayloadTraits')
     *     #### START code generated by this method
     *     http_req.headers['Content-Type'] = 'application/octet-stream'
     *     http_req.body = StringIO.new(input[:blob] || '')
     *     #### END code generated by this method
     *   end
     * end
     * }</pre>
     *
     * @param operation     operation to generate body for
     * @param inputShape    the operation's input shape
     * @param payloadMember the input shape's member marked with Payload to be serialized to the body
     * @param target        target shape of the payloadMember
     */
    protected abstract void renderPayloadBodyBuilder(OperationShape operation, Shape inputShape,
                                                     MemberShape payloadMember, Shape target);

    /**
     * Called to render an operation's body builder when it does not have a payload member.
     * The generated code should serialize all of the appropriate inputShape's members
     * to the requests body (http_req.body).  This method may also need to set headers
     * such as content type.  The class and build method skeleton is rendered outside of this method
     * and implementations only need to write the build method body.
     *
     * <p>The following example shows the generated skeleton and an example of what
     * this method is expected to render.</p>
     * <pre>{@code
     * class KitchenSinkOperation
     *   def self.build(http_req, input:)
     *     http_req.http_method = 'POST'
     *     http_req.append_path('/')
     *     http_req.headers['Content-Type'] = 'application/json'
     *     data = {}
     *     #### START code generated by this method
     *     data[:simple_struct] = Builders::SimpleStruct.build(input[:simple_struct]) unless input[:simple_struct].nil?
     *     data[:string] = input[:string] unless input[:string].nil?
     *     data[:timestamp] = Hearth::TimeHelper.to_date_time(input[:timestamp]) unless input[:timestamp].nil?
     *     http_req.body = StringIO.new(Hearth::JSON.dump(data))
     *     #### END code generated by this method
     *   end
     * end
     * }</pre>
     *
     * @param operation  operation to generate body for
     * @param inputShape operation's input shape.
     */
    protected abstract void renderBodyBuilder(OperationShape operation, Shape inputShape);

    @Override
    protected void renderOperationBuildMethod(OperationShape operation, Shape inputShape) {
        writer
                .openBlock("def self.build(http_req, input:)")
                .write("http_req.http_method = '$L'", getHttpMethod(operation))
                .call(() -> renderUriBuilder(operation, inputShape))
                .call(() -> renderQueryInputBuilder(inputShape))
                .call(() -> renderOperationBodyBuilder(operation, inputShape))
                .call(() -> renderHeadersBuilder(inputShape))
                .call(() -> renderPrefixHeadersBuilder(inputShape))
                .closeBlock("end");
    }

    protected String getHttpMethod(OperationShape operation) {
        HttpTrait httpTrait = operation.expectTrait(HttpTrait.class);

        return httpTrait.getMethod();
    }

    /**
     * @param inputShape inputShape to render for
     */
    protected void renderQueryInputBuilder(Shape inputShape) {
        // get a list of all HttpQueryParams members - these must be map shapes
        List<MemberShape> queryParamsMembers = inputShape.members()
                .stream()
                .filter((m) -> m.hasTrait(HttpQueryParamsTrait.class))
                .collect(Collectors.toList());

        // get a list of all of HttpQuery members
        List<MemberShape> queryMembers = inputShape.members()
                .stream()
                .filter((m) -> m.hasTrait(HttpQueryTrait.class))
                .collect(Collectors.toList());

        if (queryParamsMembers.isEmpty() && queryMembers.isEmpty()) {
            return;
        }

        writer.write("params = $T.new", Hearth.QUERY_PARAM_LIST);

        for (MemberShape m : queryParamsMembers) {
            String inputGetter = "input[:" + symbolProvider.toMemberName(m) + "]";
            MapShape queryParamMap = model.expectShape(m.getTarget(), MapShape.class);
            Shape target = model.expectShape(queryParamMap.getValue().getTarget());

            writer.openBlock("unless $1L.nil? || $1L.empty?", inputGetter)
                    .openBlock("$1L.each do |k, v|", inputGetter)
                    .call(() -> target.accept(new QueryMemberSerializer(queryParamMap.getValue(), "params[k] = ", "v")))
                    .closeBlock("end")
                    .closeBlock("end");
        }

        for (MemberShape m : queryMembers) {
            HttpQueryTrait queryTrait = m.expectTrait(HttpQueryTrait.class);
            String inputGetter = "input[:" + symbolProvider.toMemberName(m) + "]";
            Shape target = model.expectShape(m.getTarget());
            String setter = writer.format("params['$L'] = ", queryTrait.getValue());
            target.accept(new QueryMemberSerializer(m, setter, inputGetter));
        }

        writer.write("http_req.append_query_param_list(params)");
    }

    /**
     * @param inputShape inputShape to render for
     */
    protected void renderHeadersBuilder(Shape inputShape) {
        // get a list of all of HttpLabel members
        List<MemberShape> headerMembers = inputShape.members()
                .stream()
                .filter((m) -> m.hasTrait(HttpHeaderTrait.class))
                .collect(Collectors.toList());

        for (MemberShape m : headerMembers) {
            HttpHeaderTrait headerTrait = m.expectTrait(HttpHeaderTrait.class);
            String symbolName = ":" + symbolProvider.toMemberName(m);
            String headerSetter = "http_req.headers['" + headerTrait.getValue() + "'] = ";
            String valueGetter = "input[" + symbolName + "]";
            model.expectShape(m.getTarget()).accept(new HeaderSerializer(m, headerSetter, valueGetter));
        }
    }

    /**
     * @param inputShape inputShape to render for
     */
    protected void renderPrefixHeadersBuilder(Shape inputShape) {
        // get a list of all of HttpPrefixHeaders members
        List<MemberShape> headerMembers = inputShape.members()
                .stream()
                .filter((m) -> m.hasTrait(HttpPrefixHeadersTrait.class))
                .collect(Collectors.toList());

        for (MemberShape m : headerMembers) {
            HttpPrefixHeadersTrait headerTrait = m.expectTrait(HttpPrefixHeadersTrait.class);
            String prefix = headerTrait.getValue();
            // httpPrefixHeaders may only target map shapes
            MapShape targetShape = model.expectShape(m.getTarget(), MapShape.class);
            Shape valueShape = model.expectShape(targetShape.getValue().getTarget());

            String symbolName = ":" + symbolProvider.toMemberName(m);
            String headerSetter = "http_req.headers[\"" + prefix + "#{key}\"] = ";
            writer
                    .openBlock("input[$L]&.each do |key, value|", symbolName)
                    .call(() -> valueShape.accept(new HeaderSerializer(m, headerSetter, "value")))
                    .closeBlock("end");
        }
    }

    /**
     * @param operation  operation to render for
     * @param inputShape inputShape for the operation
     */
    protected void renderUriBuilder(OperationShape operation, Shape inputShape) {
        String uri = getHttpUri(operation);
        // need to ensure that static query params in the uri are handled first
        // get a list of all of HttpLabel members
        String[] uriParts = uri.split("[?]");
        if (uriParts.length > 1) {
            uri = uriParts[0];
            // TODO this should use append_query_param_list? interface needs to be changed in Hearth if so
            writer
                    .openBlock("CGI.parse('$L').each do |k,v|", uriParts[1])
                    .write("v.each { |q_v| http_req.append_query_param(k, q_v) }")
                    .closeBlock("end");
        }

        List<MemberShape> labelMembers = inputShape.members()
                .stream()
                .filter((m) -> m.hasTrait(HttpLabelTrait.class))
                .collect(Collectors.toList());

        if (labelMembers.size() > 0) {
            Optional<String> greedyLabel = Optional.empty();
            Matcher greedyMatch = Pattern.compile("[{]([a-zA-Z0-9_]+)[+][}]").matcher(uri);
            if (greedyMatch.find()) {
                greedyLabel = Optional.of(greedyMatch.group(1));
                uri = greedyMatch.replaceAll("%<$1>s");
            }
            String formatUri = uri
                    .replaceAll("[{]([a-zA-Z0-9_]+)[}]", "%<$1>s");
            StringBuffer formatArgs = new StringBuffer();

            for (MemberShape m : labelMembers) {
                Shape target = model.expectShape(m.getTarget());
                String getter = target.accept(new LabelMemberSerializer(m));
                if (greedyLabel.isPresent() && greedyLabel.get().equals(m.getMemberName())) {
                    formatArgs.append(
                            ",\n  " + m.getMemberName() + ": (" + getter
                                    + ").split('/').map "
                                    + "{ |s| Hearth::HTTP.uri_escape(s) }.join('/')"
                    );
                } else {
                    formatArgs.append(
                            ",\n  " + m.getMemberName() + ": Hearth::HTTP.uri_escape("
                                    + getter + ")"
                    );
                }
                writer
                        .openBlock("if $1L.empty?", getter)
                        .write("raise ArgumentError, \"HTTP label :$L cannot be empty.\"",
                                symbolProvider.toMemberName(m))
                        .closeBlock("end");
            }
            writer.openBlock("http_req.append_path(format(");
            writer.write("  '$L'$L\n)", formatUri, formatArgs.toString());
            writer.closeBlock(")");
        } else {
            writer.write("http_req.append_path('$L')", uri);
        }
    }

    /**
     * @param operation operation to get uri for
     * @return the uri
     */
    protected String getHttpUri(OperationShape operation) {
        HttpTrait httpTrait = operation.expectTrait(HttpTrait.class);
        return httpTrait.getUri().toString();
    }

    // The Input shape is combined with the OperationBuilder
    // This generates the setting of the body (if any non-http input) as if it was the Builder for the Input
    // Also marks the InputShape as generated
    protected void renderOperationBodyBuilder(OperationShape operation, Shape inputShape) {
        generatedBuilders.add(inputShape.getId());

        //determine if there are any members of the input that need to be serialized to the body
        boolean serializeBody = inputShape.members().stream().anyMatch((m) -> !m.hasTrait(HttpLabelTrait.class)
                && !m.hasTrait(HttpQueryTrait.class) && !m.hasTrait(HttpHeaderTrait.class) && !m.hasTrait(
                HttpPrefixHeadersTrait.class) && !m.hasTrait(HttpQueryParamsTrait.class));
        if (serializeBody) {
            //determine if there is an httpPayload member
            Optional<MemberShape> httpPayloadMember = inputShape.members()
                    .stream()
                    .filter((m) -> m.hasTrait(HttpPayloadTrait.class))
                    .findFirst();
            if (httpPayloadMember.isEmpty()) {
                renderBodyBuilder(operation, inputShape);
            } else {
                Shape target = model.expectShape(httpPayloadMember.get().getTarget());
                renderPayloadBodyBuilder(operation, inputShape, httpPayloadMember.get(), target);
            }
        }
    }

    protected class HeaderSerializer extends ShapeVisitor.Default<Void> {

        private final String inputGetter;
        private final String dataSetter;
        private final MemberShape memberShape;

        HeaderSerializer(MemberShape memberShape,
                         String dataSetter, String inputGetter) {
            this.inputGetter = inputGetter;
            this.dataSetter = dataSetter;
            this.memberShape = memberShape;
        }

        @Override
        protected Void getDefault(Shape shape) {
            writer.write("$1L$2L.to_s unless $2L.nil?", dataSetter, inputGetter);
            return null;
        }

        private void rubyFloat() {
            writer.write("$1L$3T.serialize($2L) unless $2L.nil?",
                    dataSetter, inputGetter, Hearth.NUMBER_HELPER);
        }

        @Override
        public Void doubleShape(DoubleShape shape) {
            rubyFloat();
            return null;
        }

        @Override
        public Void floatShape(FloatShape shape) {
            rubyFloat();
            return null;
        }

        @Override
        public Void stringShape(StringShape shape) {
            // string values with a mediaType trait are always base64 encoded.
            if (shape.hasTrait(MediaTypeTrait.class)) {
                writer.write("$1L$3T::encode64($2L).strip unless $2L.nil? || $2L.empty?",
                        dataSetter, inputGetter, RubyImportContainer.BASE64);
            } else {
                writer.write("$1L$2L unless $2L.nil? || $2L.empty?", dataSetter, inputGetter);
            }
            return null;
        }

        @Override
        public Void timestampShape(TimestampShape shape) {
            writer.write("$1L$2L unless $3L.nil?",
                    dataSetter,
                    TimestampFormat.serializeTimestamp(
                            shape, memberShape, inputGetter, TimestampFormatTrait.Format.HTTP_DATE, false),
                    inputGetter);
            return null;
        }

        @Override
        public Void listShape(ListShape shape) {
            writer.openBlock("unless $1L.nil? || $1L.empty?", inputGetter)
                    .call(() -> model.expectShape(shape.getMember().getTarget())
                        .accept(new HeaderListMemberSerializer(inputGetter, dataSetter, shape.getMember())))
                    .closeBlock("end");
            return null;
        }

        @Override
        public Void mapShape(MapShape shape) {
            // Not supported in headers
            return null;
        }

        @Override
        public Void structureShape(StructureShape shape) {
            // Not supported in headers
            return null;
        }

        @Override
        public Void unionShape(UnionShape shape) {
            // Not supported in headers
            return null;
        }
    }

    protected class HeaderListMemberSerializer extends ShapeVisitor.Default<Void> {

        private final String inputGetter;
        private final String dataSetter;
        private final MemberShape memberShape;

        protected HeaderListMemberSerializer(String inputGetter, String dataSetter, MemberShape memberShape) {
            this.inputGetter = inputGetter;
            this.dataSetter = dataSetter;
            this.memberShape = memberShape;
        }

        @Override
        protected Void getDefault(Shape shape) {
            writer.write("$1LHearth::HTTP::HeaderListBuilder.build_list($2L)",
                    dataSetter, inputGetter);
            return null;
        }

        @Override
        public Void stringShape(StringShape shape) {
            writer.write("$1LHearth::HTTP::HeaderListBuilder.build_string_list($2L)",
                    dataSetter, inputGetter);
            return null;
        }

        @Override
        public Void timestampShape(TimestampShape shape) {

            TimestampFormatTrait.Format format = memberShape
                    .getTrait(TimestampFormatTrait.class)
                    .map((t) -> t.getFormat())
                    .orElseGet(() ->
                            shape.getTrait(TimestampFormatTrait.class)
                                    .map((t) -> t.getFormat())
                                    .orElse(TimestampFormatTrait.Format.HTTP_DATE));

            switch (format) {
                case HTTP_DATE:
                    writer.write("$1LHearth::HTTP::HeaderListBuilder.build_http_date_list($2L)",
                            dataSetter, inputGetter);
                    break;
                case DATE_TIME:
                    writer.write("$1LHearth::HTTP::HeaderListBuilder.build_date_time_list($2L)",
                        dataSetter, inputGetter);
                    break;
                case EPOCH_SECONDS:
                    writer.write("$1LHearth::HTTP::HeaderListBuilder.build_epoch_seconds_list($2L)",
                        dataSetter, inputGetter);
                    break;
                default:
                    throw new CodegenException("Unexpected timestamp format to build");
            }
            return null;
        }
    }

    protected class LabelMemberSerializer extends ShapeVisitor.Default<String> {

        private final MemberShape memberShape;

        LabelMemberSerializer(MemberShape memberShape) {
            this.memberShape = memberShape;
        }

        @Override
        protected String getDefault(Shape shape) {
            String symbolName = ":" + symbolProvider.toMemberName(memberShape);
            return "input[" + symbolName + "].to_s";
        }

        @Override
        public String timestampShape(TimestampShape shape) {
            // label values are serialized using RFC 3399 date-time by default
            String symbolName = ":" + symbolProvider.toMemberName(memberShape);
            return (TimestampFormat.serializeTimestamp(
                    shape, memberShape,
                    "input[" + symbolName + "]", TimestampFormatTrait.Format.DATE_TIME, true));
        }
    }

    protected class QueryMemberSerializer extends ShapeVisitor.Default<Void> {

        private final String inputGetter;
        private final String setter;
        private final MemberShape memberShape;

        QueryMemberSerializer(MemberShape memberShape, String setter, String inputGetter) {
            this.inputGetter = inputGetter;
            this.setter = setter;
            this.memberShape = memberShape;
        }

        @Override
        protected Void getDefault(Shape shape) {
            writer.write("$1L$2L.to_s unless $2L.nil?",
                    setter, inputGetter);
            return null;
        }

        @Override
        public Void timestampShape(TimestampShape shape) {
            writer.write("$L$L unless $L.nil?",
                    setter,
                    TimestampFormat.serializeTimestamp(
                            shape, memberShape, inputGetter, TimestampFormatTrait.Format.DATE_TIME, false),
                    inputGetter);
            return null;
        }

        @Override
        public Void listShape(ListShape shape) {
            Shape target = model.expectShape(shape.getMember().getTarget());
            writer.openBlock("unless $1L.nil? || $1L.empty?", inputGetter)
                    .openBlock("$1L$2L.map do |value|", setter, inputGetter)
                    .call(() -> target.accept(new QueryMemberSerializer(shape.getMember(), "", "value")))
                    .closeBlock("end")
                    .closeBlock("end");
            return null;
        }

        @Override
        public Void mapShape(MapShape shape) {
            // Not supported in query
            return null;
        }

        @Override
        public Void structureShape(StructureShape shape) {
            // Not supported in query
            return null;
        }

        @Override
        public Void unionShape(UnionShape shape) {
            // Not supported in query
            return null;
        }
    }
}

