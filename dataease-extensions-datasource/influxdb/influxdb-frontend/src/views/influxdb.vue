<template xmlns:v-slot="http://www.w3.org/1999/XSL/Transform">
  <div>
    <el-row>
      <el-col>

        <el-form
            ref="InfluxDbForm"
            :model="form"
            :rules="rule"
            size="small"
            :disabled="disabled"
            label-width="180px"
            label-position="right"
        >
          <el-form-item :label="$t('host')" prop="configuration.host">
            <el-input v-model="form.configuration.host" autocomplete="off"/>
          </el-form-item>

          <el-form-item :label="$t('port')" prop="configuration.port">
            <el-input :placeholder="$t('enter_the_port')" v-model="form.configuration.port" autocomplete="off"/>
          </el-form-item>

          <el-form-item :label="$t('username')" prop="configuration.username">
            <el-input :placeholder="$t('one_user_name')" v-model="form.configuration.username" autocomplete="off"/>
          </el-form-item>

          <el-form-item :label="$t('password')" prop="configuration.password">
            <dePwd :placeholder="$t('input_a_password')" v-model="form.configuration.password" />
          </el-form-item>

          <el-form-item :label="$t('dataBase')" prop="configuration.dataBase">
            <el-input v-model="form.configuration.dataBase" autocomplete="off"/>
          </el-form-item>
        </el-form>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import messages from '@/de-base/lang/messages'
import dePwd from "./dePwd.vue";

export default {
  name: "influxdb",
  components: { dePwd },
  props: {
    method: String,
    request: {},
    response: {},
    editApiItem: {
      type: Boolean,
      default() {
        return false;
      }
    },
    showScript: {
      type: Boolean,
      default: true,
    },
    obj: {
      type: Object,
      default() {
        return {
          configuration: {
            initialPoolSize: 5,
            extraParams: '',
            minPoolSize: 5,
            maxPoolSize: 50,
            maxIdleTime: 30,
            acquireIncrement: 5,
            idleConnectionTestPeriod: 5,
            connectTimeout: 5
          },
          apiConfiguration: []
        }
      }
    },
  },
  data() {
    return {
      rule: {
        'configuration.host': [{required: true, message: this.$t('commons.required'), trigger: 'blur'}],
        'configuration.port': [{required: true, message: this.$t('commons.required'), trigger: 'blur'}],
        'configuration.dataBase': [{required: true, message: this.$t('commons.required'), trigger: 'blur'}],
        'configuration.username': [{required: true, message: this.$t('commons.required'), trigger: 'blur'}],
        'configuration.password': [{required: true, message: this.$t('commons.required'), trigger: 'blur'}]
      },
      canEdit: false,
      originConfiguration: {},
      height: 500,
      disabledNext: false,
      schemas: []
    }
  },
  computed: {
    form() {
      return this.obj.form
    },
    disabled() {
      return this.obj.disabled
    }
  },
  created() {
    this.$emit('on-add-languages', messages)
  },
  watch: {},
  methods: {
    executeAxios(url, type, data, callBack) {
      const param = {
        url: url,
        type: type,
        data: data,
        callBack: callBack
      }
      this.$emit('execute-axios', param)
    },
    getLogStore() {
      this.$refs["InfluxDbForm"].validate(valid => {
        if (valid) {
          const data = JSON.parse(JSON.stringify(this.form))
          data.configuration = JSON.stringify(data.configuration)
          // todo:写接口
          this.executeAxios('/datasource/getSchema', 'post', data, res => {
            this.logStores = res.data
          })
        } else {
          return false
        }
      })
    },
    validate() {
      let status = null;
      this.$refs["InfluxDbForm"].validate((val) => {
        if (val) {
          status = true
        } else {
          status = false
        }
      })
      return status
    }
  }
}
</script>

<style scoped>
.ms-query {
  background: #409EFF;
  color: white;
  height: 18px;
  border-radius: 42%;
}

.ms-header {
  background: #409EFF;
  color: white;
  height: 18px;
  border-radius: 42%;
}

.request-tabs {
  margin: 20px;
  min-height: 200px;
}

.ms-el-link {
  float: right;
  margin-right: 45px;
}
</style>
