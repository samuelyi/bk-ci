<script setup lang="ts">
import PermissionHeader from '@/components/permission-header.vue';
import AuthAside from './auth-aside.vue';
import { useI18n } from 'vue-i18n';
import {
  ref,
} from 'vue';
const { t } = useI18n();

const navs = ref([
  { name: t('我的授权'), url: '' },
]);
</script>

<template>
  <permission-header :navs="navs"></permission-header>
  <section class="permission-home">
    <!-- 菜单栏 -->
    <auth-aside></auth-aside>
    <router-view class="permission-main"></router-view>
  </section>
</template>

<style lang="postcss" scoped>
  .permission-home {
    display: flex;
    flex: 1;
    overflow: auto;
  }
  .permission-main {
    flex: 1;
    overflow: hidden;
  }
</style>
