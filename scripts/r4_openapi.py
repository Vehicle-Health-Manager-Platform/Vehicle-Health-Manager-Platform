"""R4 completion contracts; called by the canonical OpenAPI generator."""


def add_r4_completion(document, operation):
    schemas = document['components']['schemas']
    strict = lambda props: {'type': 'object', 'additionalProperties': False,
                            'required': list(props), 'properties': props}
    ref = lambda name: {'$ref': '#/components/schemas/' + name}
    nullable = lambda schema: {'anyOf': [schema, {'type': 'null'}]}
    integer = lambda low, high: {'type': 'integer', 'minimum': low, 'maximum': high}
    text = lambda size: {'type': 'string', 'minLength': 1, 'maxLength': size}
    array = lambda schema, size: {'type': 'array', 'maxItems': size, 'items': schema}
    identifier = integer(1, 9007199254740991)
    version = integer(0, 9007199254740991)
    revision = integer(0, 2147483647)
    date = {'type': 'string', 'format': 'date', 'pattern': '^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$'}
    decimal = {'type': 'string', 'pattern': r'^-?(0|[1-9][0-9]{0,8})(\.[0-9]{1,3})?$'}
    metrics = ['OIL', 'BRAKE_PAD', 'TIRE', 'TRACTION_BATTERY', 'COOLANT', 'AUX_BATTERY']
    metric = {'enum': metrics}
    units = {'enum': ['KM', 'PERCENT', 'MM', 'KPA', 'V', 'CELSIUS']}
    provenance = {'enum': ['OWNER_MEASURED', 'OWNER_TRANSCRIBED']}
    schemas['HealthMeasurement'] = strict({'measurement_name': text(80), 'value': decimal,
        'unit': units, 'measured_on': date, 'method': text(120), 'provenance': provenance})
    schemas['HealthObservation'] = strict({'metric': metric, 'revision': revision,
        'archive_id': nullable(identifier), 'recorded_date': nullable(date),
        'measurement': {'anyOf': [ref('HealthMeasurement'), strict({})]},
        'status': {'enum': ['NOT_SET', 'RECORDED_UNVERIFIED', 'SOURCE_UNAVAILABLE']}})
    common = {'source_reference': text(256), 'source_checked_on': date, 'valid_until': date,
              'source_confirmed': {'const': True}}
    applicable = strict({**common, 'applicability': {'const': 'APPLICABLE'},
        'measurement_name': text(80), 'unit': units, 'method': text(120), 'provenance': provenance,
        'max_age_days': integer(1, 3650), 'weight': integer(1, 100),
        'bands': {**array(strict({'upper_bound': nullable(decimal), 'score': integer(0, 100)}), 20), 'minItems': 1},
        'action_project_id': nullable(identifier), 'action_score_below': nullable(integer(1, 100))})
    inactive = strict({**common, 'applicability': {'const': 'NOT_APPLICABLE'},
        **{key: {'type': 'null'} for key in ['measurement_name', 'unit', 'method', 'provenance', 'action_project_id', 'action_score_below']},
        'max_age_days': {'const': 0}, 'weight': {'const': 0}, 'bands': array({}, 0)})
    schemas['HealthSourceRule'] = {'oneOf': [applicable, inactive], 'description':
        '精确车型来源声明，不预置阈值。单位按指标白名单，有限上界严格递增且仅最后一段为null正无穷，边界含等号；动作两字段同时为空或同时填写。日期/范围/适用性联动见R4_COMPLETION.md。'}
    schemas['HealthPolicy'] = strict({'model_id': identifier, 'metric': metric, 'version': version,
                                    'rule': nullable(ref('HealthSourceRule'))})
    schemas['HealthPolicyInput'] = strict({'expected_version': integer(0, 9007199254740990),
                                          'rule': nullable(ref('HealthSourceRule'))})
    schemas['HealthConfirmationInput'] = strict({'expected_revision': integer(0, 2147483646),
        'confirmed': {'type': 'boolean'}, 'observation_revision': revision, 'policy_version': version})
    schemas['HealthConfirmationReceipt'] = strict({'vehicle_id': identifier, 'metric': metric, 'revision': revision})
    prefs = {'maintenance_days': integer(0, 365), 'maintenance_km': integer(0, 100000),
             'insurance_days': integer(0, 365), 'inspection_days': integer(0, 365),
             'maintenance_project_id': nullable(identifier)}
    schemas['ReminderPreference'] = strict({**prefs, 'revision': revision})
    schemas['ReminderPreferenceInput'] = strict({**prefs, 'expected_revision': integer(0, 2147483646)})
    schemas['ReminderPreferenceInput']['required'].remove('maintenance_project_id')
    schemas['ReminderPreferenceInput']['description'] = '兼容旧五字段body；未提供项目时清空项目选择。默认提前范围均为0。'
    schemas['HealthTrend'] = strict({'status': {'enum': ['NOT_AVAILABLE', 'INSUFFICIENT', 'COMPARABLE_UNVERIFIED']},
        'points': array(strict({'revision': integer(1, 2147483647), 'archive_id': identifier,
                              'measured_on': date, 'value': decimal, 'unit': units}), 51),
        'delta': nullable({'type': 'string', 'pattern': r'^-?(0|[1-9][0-9]{0,9})(\.[0-9]{1,3})?$'}),
        'truncated': {'type': 'boolean'}})
    schemas['HealthMetric'] = strict({'metric': metric, 'status': {'enum': ['MODEL_UNKNOWN', 'POLICY_NOT_SET',
        'POLICY_INVALID', 'POLICY_EXPIRED', 'NOT_APPLICABLE', 'SOURCE_UNAVAILABLE', 'NOT_SET', 'EVIDENCE_INVALID',
        'NOT_COMPARABLE', 'EVIDENCE_EXPIRED', 'CONFIRMATION_REQUIRED', 'REFERENCE_READY']},
        'rule_version': version, 'rule': nullable(ref('HealthSourceRule')), 'observation': ref('HealthObservation'),
        'confirmation': strict({'revision': revision, 'confirmed': {'type': 'boolean'}}),
        'score': nullable(integer(0, 100)), 'trend': ref('HealthTrend'),
        'action_status': {'enum': ['NONE', 'EVIDENCE_UNKNOWN', 'NOT_TRIGGERED', 'PROJECT_UNAVAILABLE',
                                  'APPLICABILITY_UNKNOWN', 'CONFLICTING_APPLICABILITY', 'TRIGGERED']}})
    schemas['HealthRequiredProject'] = strict({'project_id': identifier, 'project_name': text(128),
        'category': integer(1, 6), 'tier': {'const': 'MUST'}, 'evidence': {**array(strict({
            'metric': metric, 'policy_version': version, 'observation_revision': revision,
            'confirmation_revision': revision, 'action_rule_version': identifier,
            'value': decimal, 'unit': units, 'measured_on': date, 'source_reference': text(256),
            'project_source_reference': text(256)}), 6), 'minItems': 1}})
    kinds = ['FAULT', 'MAINTENANCE', 'INSURANCE', 'INSPECTION', 'VIOLATION']
    action = {'oneOf': [strict({'type': {'enum': ['ARCHIVE', 'TARGET']}, 'archive_id': identifier}),
                        strict({'type': {'const': 'PROJECT'}, 'project_id': identifier}),
                        strict({'type': {'const': 'SERVICES'}})]}
    alert = strict({'kind': {'enum': kinds}, 'severity': {'enum': ['OWNER_URGENT', 'DUE', 'PENDING', 'UPCOMING']},
        'title': text(400), 'archive_id': identifier, 'source_revision': integer(1, 2147483647),
        'source_reference': text(256), 'remaining_days': nullable({'type': 'integer'}),
        'remaining_km': nullable({'type': 'integer'}), 'preference_revision': revision, 'action': action})
    schemas['HealthDashboard'] = strict({'vehicle': schemas['VehiclePlanEvidence']['properties']['vehicle'],
        'today': date, 'overall': {'oneOf': [strict({'status': {'const': 'UNKNOWN'},
            **{key: {'type': 'null'} for key in ['score', 'weighted_sum', 'weight_total']}}),
            strict({'status': {'const': 'REFERENCE_READY'}, 'score': integer(0, 100),
                    'weighted_sum': integer(0, 60000), 'weight_total': integer(1, 600)})]},
        'metrics': {**array(ref('HealthMetric'), 6), 'minItems': 6},
        'required_projects': array(ref('HealthRequiredProject'), 6),
        'reminders': {**array(strict({'kind': {'enum': kinds}, 'status': {'enum': ['NOT_SET', 'UNAVAILABLE',
            'INACTIVE', 'UNKNOWN', 'PLANNED', 'DUE', 'UPCOMING', 'ATTENTION', 'UNCONFIRMED', 'CLEAR']},
            'items': array(alert, 200), 'limited': {'type': 'boolean'}}), 5), 'minItems': 5},
        'preference': ref('ReminderPreference')})
    schemas['RatedQuote'] = strict({**schemas['MerchantQuote']['properties'], 'rating_summary': ref('PublicRatingSummary')})
    schemas['RatedQuotePage'] = strict({'items': array(ref('RatedQuote'), 20), 'total': {'type': 'integer', 'minimum': 0},
        'page': integer(1, 1000000), 'page_size': {'const': 20}})
    schemas['ServiceSearchInput'] = strict({'query': text(80), 'category': integer(0, 6), 'page': integer(1, 1000000)})
    schemas['ServiceSearchPage'] = strict({'items': array(strict({'id': identifier, 'project_name': {'type': 'string'},
        'category': integer(1, 6), 'base_price_low': {'type': 'string'}, 'base_price_high': {'type': 'string'}}), 20),
        'total': {'type': 'integer', 'minimum': 0}, 'page': integer(1, 1000000), 'page_size': {'const': 20}})
    routes = [
        ('get', '/api/vehicle/{vehicle}/health-dashboard', '本车来源健康分、趋势、五类提醒', None, 'HealthDashboard', 'OWNER'),
        ('get', '/api/admin/health-policies/{model}/{metric}', '读取精确车型指标来源规则', None, 'HealthPolicy', 'OPERATOR(can_catalog)'),
        ('put', '/api/admin/health-policies/{model}/{metric}', '版本化发布或撤回来源规则', 'HealthPolicyInput', 'HealthPolicy', 'OPERATOR(can_catalog)'),
        ('put', '/api/vehicle/{vehicle}/health/{metric}/confirmation', '确认或撤回当前规则与读数用途', 'HealthConfirmationInput', 'HealthConfirmationReceipt', 'OWNER'),
        ('put', '/api/vehicle/{vehicle}/reminder-preference', '配置本人提前范围与保养项目', 'ReminderPreferenceInput', 'ReminderPreference', 'OWNER'),
        ('get', '/api/service/project/{id}/rating-quotes', '按实时获授权公开星级排序报价', None, 'RatedQuotePage', 'OWNER'),
        ('post', '/api/service/project-search', '只读关键词标准项目搜索', 'ServiceSearchInput', 'ServiceSearchPage', 'OWNER'),
    ]
    for method, path, summary, body, result, roles in routes:
        op = operation(method, path, summary, 'F03,F04,F05,F06')
        op['x-roles'] = roles
        op['x-implementation-status'] = 'r4-completion-implemented'
        op['description'] = 'no-store；严格字段/有效身份与当前来源复核；规则不回填，未知不算正常。详见R4_COMPLETION.md。'
        for param in op['parameters']:
            param['schema'] = metric if param['name'] == 'metric' else identifier
        if method == 'put':
            op['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True,
                                     'schema': {'type': 'string', 'format': 'uuid'}})
        if method == 'post':
            op['parameters'] = []
            op['x-read-only'] = True
        if body:
            op['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
        if 'rating-quotes' in path:
            op['parameters'].extend([{'name': 'page', 'in': 'query', 'schema': integer(1, 1000000)},
                {'name': 'page_size', 'in': 'query', 'schema': {'type': 'integer', 'const': 20}},
                {'name': 'region_code', 'in': 'query', 'schema': {'type': 'string', 'pattern': '^[0-9]{6}$'}}])
        op['responses'].update({'404': {'description': '本人车辆或有效目录/来源不可用'},
            '409': {'description': '规则/读数/确认/偏好版本冲突'}, '503': {'description': '数据库或审计服务不可用'}})
        op['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'),
            {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        document['paths'].setdefault(path, {})[method] = op

    # Replace the older three-type structured request with all current private source fields.
    source = {'owner_confirmed': {'type': 'boolean'}, 'source_reference': text(256), 'source_checked_on': date}
    names_by_type = {
        1: 'provider_name service_items performed_mileage service_date oil_spec parts_used next_due_date next_due_mileage',
        2: 'provider_name repair_items performed_mileage repair_date problem_description parts_used result_notes',
        3: 'insurer_name policy_number coverage_type coverage_start coverage_end policy_holder insured_name insured_vehicle_plate insured_vin coverage_amount',
        6: 'case_number occurred_on location description penalty_amount points violation_status handled_on authority',
        7: 'inspection_station inspection_result valid_until inspection_date certificate_number remarks',
        8: 'owner_name address plate_number vehicle_type brand_model vin engine_number use_character register_date issue_date issuing_authority record_number gross_mass_kg unladen_mass_kg approved_passengers inspection_valid_until',
        9: 'holder_name license_number nationality address birth_date first_issue_date permitted_classes valid_from valid_until validity_type issuing_authority',
        10: 'fault_code fault_description observed_on fault_status cleared_on attention_level',
    }
    enums = {'coverage_type': ['COMPULSORY', 'COMMERCIAL', 'COMBINED', 'OTHER'],
        'inspection_result': ['PASSED', 'FAILED', 'UNKNOWN'], 'violation_status': ['PENDING', 'HANDLED', 'UNKNOWN'],
        'fault_status': ['ACTIVE', 'RESOLVED', 'UNKNOWN'], 'attention_level': ['NORMAL', 'URGENT'],
        'validity_type': ['FINITE', 'LONG_TERM']}
    date_fields = set('service_date repair_date next_due_date coverage_start coverage_end occurred_on handled_on inspection_date valid_until register_date issue_date inspection_valid_until birth_date first_issue_date valid_from observed_on cleared_on'.split())
    variants = []
    for kind, names in names_by_type.items():
        props = dict(source)
        for name in names.split():
            if name in enums: value = {'enum': enums[name]}
            elif name in date_fields: value = date
            elif name in ['performed_mileage', 'next_due_mileage']: value = integer(0, 2147483647)
            elif name in ['points', 'approved_passengers']: value = integer(0, 1000)
            elif name in ['service_items', 'repair_items', 'parts_used']:
                value = {**array(text(80), 20), 'minItems': 1, 'uniqueItems': True}
            elif name in ['coverage_amount', 'penalty_amount']:
                value = {'type': 'string', 'pattern': r'^(0|[1-9][0-9]{0,7})\.[0-9]{2}$'}
            elif name in ['gross_mass_kg', 'unladen_mass_kg']:
                value = {'type': 'string', 'pattern': r'^(0|[1-9][0-9]{0,8})(\.[0-9]{1,3})?$'}
            else:
                value = text(200 if name in ['description', 'fault_description', 'problem_description', 'result_notes', 'remarks', 'address']
                    else 64 if name in ['policy_number', 'case_number', 'license_number', 'certificate_number'] else 100)
            props[name] = value
        variants.append({'type': 'object', 'additionalProperties': False, 'properties': props,
                         'description': f'archive_type={kind}；允许未确认部分资料，owner_confirmed=true须完整必填来源与类型字段，见R4_COMPLETION.md。'})
    op = document['paths']['/api/archive/{id}/structured']['put']
    op['description'] = '本人类型1/2/3/6/7/8/9/10手动资料；本人确认不代表官方核验。空fields清空，既有版本/审计/幂等不变；新类型8/9/10不进入分享或自动AI上下文。详见R4_COMPLETION.md。'
    op['requestBody']['content']['application/json']['schema']['properties']['fields'] = {'anyOf': variants}
    document['paths']['/api/archive/{id}/structured']['get']['description'] = op['description']
