"""Isolated private input material upload, no recognition or image-policy expansion."""
def add_r9a2b(d, operation):
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    fields = {'material_id': ident, 'vehicle_id': ident, 'purpose': {'enum': ['AI', 'ARCHIVE']},
              'content_type': {'enum': ['audio/mpeg', 'application/pdf']}, 'size_bytes': {'type': 'integer', 'minimum': 1, 'maximum': 10485760},
              'duration_ms': {'type': 'integer', 'minimum': 0, 'maximum': 60000}, 'pages': {'type': 'integer', 'minimum': 0, 'maximum': 50}, 'scan_status': {'const': 'CLEAN'}}
    d['components']['schemas']['InputMaterialReceipt'] = {'type': 'object', 'additionalProperties': False, 'required': list(fields), 'properties': fields}
    d['components']['schemas']['InputMaterialReceipt']['oneOf'] = [
        {'properties': {'content_type': {'const': 'audio/mpeg'}, 'size_bytes': {'maximum': 3145728}, 'duration_ms': {'minimum': 1000}, 'pages': {'const': 0}}},
        {'properties': {'content_type': {'const': 'application/pdf'}, 'duration_ms': {'const': 0}, 'pages': {'minimum': 1}}}]
    d['components']['schemas']['InputMaterialAccess'] = {'type': 'object', 'additionalProperties': False, 'required': ['url', 'expires_at'], 'properties': {'url': {'type': 'string', 'format': 'uri'}, 'expires_at': {'type': 'string', 'format': 'date-time'}}}
    for method, suffix, result in [('post', '/upload', 'InputMaterialReceipt'), ('get', '/{id}/access', 'InputMaterialAccess')]:
        path = '/api/input-materials/{purpose}/{vehicleId}' + suffix
        item = operation(method, path, '本人车辆辅助材料受控上传' if method == 'post' else '本人原用途辅助材料短时附件访问', 'F03,F05')
        item['x-roles'] = 'OWNER'
        item['description'] = 'no-store。默认关闭 INPUT_MATERIAL_UPLOAD_ENABLED；仅本人有效车辆、正式会话。独立input_material表，不接受或返回业务图片file_id，不做ASR/OCR。MP3 16kHz单声道48kbps、1–60秒、3MiB；静态PDF 1–50页10MiB，无加密/表单/注释/动作/附件。CLEAN才写入；用途及车辆隔离幂等，同键不同内容409。签发60秒强制attachment/no-store，复核会话/车辆/用途及对象元数据。详见CONTROLLED_INPUT_MATERIALS.md。'
        item['parameters'] = [
            {'name': 'purpose', 'in': 'path', 'required': True, 'schema': {'enum': ['AI', 'ARCHIVE']}},
            {'name': 'vehicleId', 'in': 'path', 'required': True, 'schema': ident}]
        if method == 'post':
            item['parameters'] += [{'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}},
                                   {'name': 'X-Material-Kind', 'in': 'header', 'required': True, 'schema': {'enum': ['audio', 'pdf']}}]
            item['requestBody'] = {'required': True, 'content': {'multipart/form-data': {'schema': {'type': 'object', 'additionalProperties': False, 'required': ['file'], 'properties': {'file': {'type': 'string', 'format': 'binary'}}}}}}
        else:
            item['parameters'].append({'name': 'id', 'in': 'path', 'required': True, 'schema': ident})
        for status, message in [('400','参数或multipart无效'), ('404','车辆或材料不可用'), ('409','幂等冲突或处理中'), ('413','材料/请求过大'), ('422','格式或扫描未通过'), ('429','限流/并发限制'), ('503','开关关闭或设施不可用')]:
            item['responses'][status] = {'description': message}
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        d['paths'][path] = {method: item}
