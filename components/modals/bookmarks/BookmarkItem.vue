<template>
  <div
    :key="bookmark.time"
    :id="`bookmark-row-${bookmark.time}`"
    class="flex items-center px-1 py-4 justify-start relative select-none"
    :class="[highlight ? 'bg-bg bg-opacity-60' : 'bg-opacity-20', selected ? 'bg-primary bg-opacity-20' : '']"
    @touchstart="startTouch"
    @touchmove="moveTouch"
    @touchend="endTouch"
    @touchcancel="cancelTouch"
    @mousedown="startMouse"
    @mouseup="endMouse"
    @mouseleave="cancelMouse"
  >
    <div class="flex-grow overflow-hidden px-2">
      <div class="flex items-center mb-0.5">
        <div v-if="multiSelectMode" class="mr-2">
          <span class="material-symbols text-2xl" :class="selected ? 'text-success' : 'text-fg-muted'">{{ selected ? 'check_circle' : 'radio_button_unchecked' }}</span>
        </div>
        <i v-else class="material-symbols text-lg pr-1 -mb-1" :class="{ 'text-success fill': highlight, 'text-fg-muted': !highlight }">bookmark</i>
        <p class="truncate text-sm">
          {{ bookmark.title }}
        </p>
      </div>
      <p class="text-sm font-mono text-fg-muted flex items-center pl-8" v-if="multiSelectMode"><span class="material-symbols text-base pl-px pr-1">schedule</span>{{ $secondsToTimestamp(bookmark.time / playbackRate) }}</p>
      <p class="text-sm font-mono text-fg-muted flex items-center" v-else><span class="material-symbols text-base pl-px pr-1">schedule</span>{{ $secondsToTimestamp(bookmark.time / playbackRate) }}</p>
    </div>
    <div v-if="!multiSelectMode" class="h-full flex items-center justify-end transform w-16 pr-2" @click.stop @mousedown.stop @touchstart.stop>
      <span class="material-symbols text-2xl mr-2 text-fg hover:text-yellow-400 cursor-pointer" @click.stop="editClick">edit</span>
      <span class="material-symbols text-2xl text-fg hover:text-error cursor-pointer" @click.stop="deleteClick">delete</span>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    bookmark: {
      type: Object,
      default: () => {}
    },
    highlight: Boolean,
    playbackRate: Number,
    selected: Boolean,
    multiSelectMode: Boolean
  },
  data() {
    return {
      longPressTimer: null,
      longPressTriggered: false,
      startTouchX: 0,
      startTouchY: 0,
      isScrolling: false
    }
  },
  methods: {
    // Touch Events
    startTouch(e) {
      if (this.multiSelectMode) return // In multi-select, standard click handling is fine (via touchend)

      this.longPressTriggered = false
      this.isScrolling = false
      this.startTouchX = e.touches[0].clientX
      this.startTouchY = e.touches[0].clientY

      this.longPressTimer = setTimeout(() => {
        if (!this.isScrolling) {
          this.longPressTriggered = true
          this.$emit('longpress', this.bookmark)
        }
      }, 500)
    },
    moveTouch(e) {
      if (!this.longPressTimer) return

      const touchX = e.touches[0].clientX
      const touchY = e.touches[0].clientY

      // If moved more than 10px, consider it scrolling
      if (Math.abs(touchX - this.startTouchX) > 10 || Math.abs(touchY - this.startTouchY) > 10) {
        this.isScrolling = true
        clearTimeout(this.longPressTimer)
        this.longPressTimer = null
      }
    },
    endTouch(e) {
      if (this.longPressTimer) {
        clearTimeout(this.longPressTimer)
        this.longPressTimer = null
      }

      if (this.multiSelectMode) {
        // Prevent ghost clicks if needed, but mostly rely on simple tap
        // We handle selection toggle here
        this.$emit('click', this.bookmark)
        return
      }

      if (!this.longPressTriggered && !this.isScrolling) {
        this.$emit('click', this.bookmark)
      }
    },
    cancelTouch() {
      if (this.longPressTimer) {
        clearTimeout(this.longPressTimer)
        this.longPressTimer = null
      }
      this.isScrolling = false
    },

    // Mouse Events (for desktop testing/usage)
    startMouse(e) {
      if (this.multiSelectMode) return
      if (e.button !== 0) return

      this.longPressTriggered = false
      this.longPressTimer = setTimeout(() => {
        this.longPressTriggered = true
        this.$emit('longpress', this.bookmark)
      }, 500)
    },
    endMouse(e) {
      if (this.longPressTimer) {
        clearTimeout(this.longPressTimer)
        this.longPressTimer = null
      }

      if (this.multiSelectMode) {
        if (e.button === 0) this.$emit('click', this.bookmark)
        return
      }

      if (!this.longPressTriggered && e.button === 0) {
        this.$emit('click', this.bookmark)
      }
    },
    cancelMouse() {
      if (this.longPressTimer) {
        clearTimeout(this.longPressTimer)
        this.longPressTimer = null
      }
    },

    deleteClick() {
      this.$emit('delete', this.bookmark)
    },
    editClick() {
      this.$emit('edit', this.bookmark)
    }
  }
}
</script>
