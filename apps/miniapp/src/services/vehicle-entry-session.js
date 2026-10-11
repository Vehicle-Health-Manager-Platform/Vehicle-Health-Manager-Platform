import {watch} from 'vue'
import {ownerSession} from './owner-session.js'
import {createVehiclePrefillStore} from './vehicle-entry.js'
export const vehicleEntryPrefill=createVehiclePrefillStore()
watch(()=>ownerSession.accessToken,vehicleEntryPrefill.clear,{flush:'sync'})
