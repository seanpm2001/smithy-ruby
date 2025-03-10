# frozen_string_literal: true

require_relative 'hearth/api_error'
require_relative 'hearth/auth_option'

# must be required before auth_schemes
require_relative 'hearth/identities'
require_relative 'hearth/auth_schemes'
require_relative 'hearth/anonymous_auth_resolver'

require_relative 'hearth/block_io'
require_relative 'hearth/checksums'
require_relative 'hearth/client'
require_relative 'hearth/configuration'
require_relative 'hearth/config/env_provider'
require_relative 'hearth/config/resolver'
require_relative 'hearth/connection_pool'
require_relative 'hearth/context'
require_relative 'hearth/dns'

# must be required before http
require_relative 'hearth/middleware'
require_relative 'hearth/networking_error'
require_relative 'hearth/request'
require_relative 'hearth/response'

# must be required before endpoint rules
require_relative 'hearth/structure'

require_relative 'hearth/endpoint_rules'
require_relative 'hearth/http'
require_relative 'hearth/identity_provider'
require_relative 'hearth/interceptor'
require_relative 'hearth/interceptors'
require_relative 'hearth/interceptor_context'
require_relative 'hearth/interceptor_list'
require_relative 'hearth/json'
require_relative 'hearth/plugin_list'
require_relative 'hearth/middleware_stack'
require_relative 'hearth/number_helper'
require_relative 'hearth/output'
require_relative 'hearth/query/param'
require_relative 'hearth/query/param_list'
require_relative 'hearth/refreshing_identity_provider'
require_relative 'hearth/retry'
require_relative 'hearth/signers'
require_relative 'hearth/stubs'
require_relative 'hearth/time_helper'
require_relative 'hearth/union'
require_relative 'hearth/validator'
require_relative 'hearth/waiters/poller'
require_relative 'hearth/waiters/waiter'
require_relative 'hearth/xml'

module Hearth
  VERSION = File.read(File.expand_path('../VERSION', __dir__)).strip
end
